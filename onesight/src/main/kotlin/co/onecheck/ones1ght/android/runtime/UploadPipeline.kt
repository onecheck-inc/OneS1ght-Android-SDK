package co.onecheck.ones1ght.android.runtime

//
//  UploadPipeline.kt
//  좌표 수집 → 서버 전송. 다운샘플(position_rate_hz)·버퍼·전송 시점(300건 도달/60초 타이머/종료·배경)과
//  실패 뒤 backoff 를 맡는다.
//
//  SessionCoordinator 에서 떼어 냈다(감사 SP-C2 · iOS K7 — 같은 이름). 스레드: 코어 디스패처([scope])에서만 부른다.
//
//  배치 정책 (사양서 §6.8 은 100건/5분 "권장" — 2026-08-20 300건/60초로 조정.
//  4Hz 에서는 300건(=75초)보다 60초 타이머가 먼저 걸려 실질 60초·240건 주기가 된다. 테스트에서 작게 주입.)
//
//  포팅 원본: UploadPipeline.swift(iOS #55).
//

import co.onecheck.ones1ght.android.internal.Iso8601
import co.onecheck.ones1ght.android.model.Coordinates
import co.onecheck.ones1ght.android.model.PositionPoint
import co.onecheck.ones1ght.android.model.ReqPositionBulk
import co.onecheck.ones1ght.android.network.ApiClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal class UploadPipeline(
    private val api: ApiClient,
    private val reporter: SdkReporter,
    private val scope: CoroutineScope,
    private val clock: () -> Long,
    private val flushThreshold: Int,
    private val flushIntervalMs: Long,
    maxPerRequest: Int,
    /** 지금 세션의 귀속 키(프로필·방문) — 프로필이 없으면 보내지 않는다. */
    private val owner: () -> Owner?,
) {
    /** 좌표의 귀속 키 — 프로필과 방문. */
    class Owner(val profileId: String, val visitorId: String)

    private val buffer = TrajectoryBuffer(maxPerRequest)

    /** 전송 대기 좌표 수. */
    val pendingCount: Int get() = buffer.count

    /** 서버 전송 주기(Hz) — verify 가 내려준 값. 범위 밖·미회신은 기본값(4Hz)으로 접힌 값이 들어온다. */
    var positionRateHz: Int = PositionRate.DEFAULT_HZ

    /** 서버 전송용 좌표 다운샘플 기준 시각 — 판정 입력은 솎지 않는다. */
    private var lastRecordedAt: Long? = null

    private var flushJob: Job? = null

    /**
     * 좌표 전송 실패 뒤 임계값 전송을 다시 시도해도 되는 시각 — 실패할 때마다 [POSITION_RETRY_MIN_MS] 부터 두 배씩
     * [POSITION_RETRY_MAX_MS] 까지 늘고, 성공하면 0. 60초 타이머·종료·send() 는 이것과 무관하게 보낸다.
     *
     * 왜: 오프라인에서 임계값을 넘긴 뒤엔 좌표(4Hz)마다 전송 → 실패 → E5001(ERROR) → 로그 전송까지 초당 최대
     * 8요청이 났다(iOS S5).
     */
    private var positionRetryAt = 0L
    private var positionRetryDelayMs = 0L

    /** 임계값 전송이 이미 예약·진행 중인가 — 좌표마다 또 예약하지 않는다. */
    private var thresholdFlushPending = false

    /** 새 방문 — 다운샘플 기준을 지운다(타이머는 측위가 실제로 켜진 뒤 [startTimer] 로). */
    fun beginSession() {
        lastRecordedAt = null
    }

    /** 60초 타이머를 건다(이미 있으면 새로). */
    fun startTimer() {
        flushJob?.cancel()
        flushJob = scope.launch {
            while (isActive) {
                delay(flushIntervalMs)
                flush()
            }
        }
    }

    /** 60초 타이머를 내린다. */
    fun stopTimer() {
        flushJob?.cancel()
        flushJob = null
    }

    /** 좌표 한 점. 다운샘플을 통과하면 쌓고, 임계에 닿으면(실패 뒤 대기 중이 아니면) 보낸다. */
    fun record(coordinates: Coordinates, floorId: String, atMs: Long) {
        // ⚠️ 서버 전송분만 솎는다. 존 판정(provider 내부)은 원속도 그대로.
        if (!shouldRecord(atMs)) return
        buffer.append(PositionPoint(floorId = floorId, coordinates = coordinates, capturedAt = Iso8601.format(atMs)))
        // 임계값 전송 — 이미 예약됐거나 실패 뒤 대기 중이면 좌표마다 다시 걸지 않는다(iOS S5).
        if (buffer.count >= flushThreshold && !thresholdFlushPending && clock() >= positionRetryAt) {
            thresholdFlushPending = true
            scope.launch {
                try {
                    flush()
                } finally {
                    thresholdFlushPending = false
                }
            }
        }
    }

    /** 쌓인 좌표를 지금 보낸다. */
    suspend fun flush() {
        buffer.flush(::send)
    }

    /** 쌓인 좌표를 보내지 않고 버린다. */
    fun discard() {
        buffer.clear()
    }

    /**
     * position_rate_hz 다운샘플 판정. 경계에 10% 여유를 둔다 — 4Hz 입력이면 간격이 0.25초
     * 언저리로 흔들려, 정확히 1/rate 로 자르면 절반이 버려진다.
     */
    private fun shouldRecord(atMs: Long): Boolean {
        val minGapMs = (1000.0 / positionRateHz) * 0.9
        val last = lastRecordedAt
        if (last != null && (atMs - last) < minGapMs) return false
        lastRecordedAt = atMs
        return true
    }

    /** 좌표 벌크 전송 (buffer 의 sender) — true = 200 */
    private suspend fun send(batch: List<PositionPoint>): Boolean {
        val owner = owner() ?: return false
        val req = ReqPositionBulk(owner.profileId, owner.visitorId, SdkPlatform.NAME, batch)
        return try {
            val res = api.sendPositionLogs(req)
            reporter.log(LogLevel.INFO, SdkLocalized.t("coord.logsSent", batch.size, res.acceptedCount))
            positionRetryDelayMs = 0L
            positionRetryAt = 0L
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            positionRetryDelayMs = (positionRetryDelayMs * 2).coerceIn(POSITION_RETRY_MIN_MS, POSITION_RETRY_MAX_MS)
            positionRetryAt = clock() + positionRetryDelayMs
            reporter.reportApi(e, "positions=${batch.size}", message = SdkLocalized.t("coord.logsFail", batch.size))
            false
        }
    }

    internal companion object {
        /** 좌표 전송 실패 뒤 첫 대기 · 최대 대기(임계값 전송만 — 60초 타이머는 그대로). */
        const val POSITION_RETRY_MIN_MS: Long = 5_000L
        const val POSITION_RETRY_MAX_MS: Long = 60_000L
    }
}
