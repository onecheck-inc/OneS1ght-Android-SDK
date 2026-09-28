package co.onecheck.ones1ght.android.zone

//
//  ZoneEngine.kt
//  도면 로컬 좌표 기반 Zone 판정 — 순수 수학(코어, android.* 미사용).
//
//  · 판단은 sampleIntervalMs(기본 1초)에 1번만 한다 — 그 사이 들어온 좌표는 버린다.
//  · confirmCount(기본 3) 연속 같은 판정이어야 IN/OUT 확정 — 경계선 좌표 지터(깜빡임)를
//    카운터 리셋 방식으로 흡수한다.
//  · 존 선택은 zones.firstOrNull { contains(p) } — 목록 순서상 첫 존, priority 는 보지 않는다.
//  · DWELL 은 iOS UwbAreaJudge 계약을 따른다 — IN 확정 시각부터 dwellSeconds 뒤 여전히 같은
//    활성 존이면 1회만 발행한다(iOS ZoneEngine 자체의 5초 간격 반복 재발화는 쓰지 않는다).
//  · 콘솔 존의 in_dist·in_count 등 옛 판정 파라미터는 여기서 쓰지 않는다 — 존이 있는 최초
//    apply 에서 그 사실을 uwb.paramsIgnored WARN 으로 한 번만 남긴다(이후 존을 다시
//    apply 해도 반복하지 않는다 — 층마다 반복되면 로그가 덮인다).
//
//  포팅 원본: ZoneEngine.swift(판정 루프) + UwbAreaJudge.swift(DWELL 1회 규칙·reset·
//  paramsIgnored 경고).
//

import co.onecheck.ones1ght.android.model.Position
import co.onecheck.ones1ght.android.model.Zone
import co.onecheck.ones1ght.android.model.ZoneEvent
import co.onecheck.ones1ght.android.runtime.LogLevel
import co.onecheck.ones1ght.android.runtime.SdkLocalized
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 체류(DWELL) 1회 발화를 위한 지연 스케줄러 계약. 테스트가 실제 시간을 흘리지 않고 시계를
 * 직접 제어할 수 있도록 [ZoneEngine] 과 분리해 뒀다.
 *
 * ⚠️ [schedule] 이 돌려준 콜백([action])은 [ZoneEngine.ingest]/[ZoneEngine.apply]/
 * [ZoneEngine.reset] 을 부르는 스레드와 **같은 스레드(디스패처)** 에서 실행돼야 한다 —
 * `activeZoneId` 등 코어 상태는 동기화 없이 단일 디스패처 한 곳에서만 바뀐다는 전제이므로,
 * 다른 스레드에서 콜백을 돌리면 그 전제가 깨진다(사양: "코어 상태는 주입된
 * CoroutineDispatcher 한 곳에서만 바꾼다").
 */
public fun interface DwellScheduler {
    public fun schedule(delayMs: Long, action: () -> Unit): Cancellable
}

/** [DwellScheduler.schedule] 이 돌려주는 취소 핸들. */
public fun interface Cancellable {
    public fun cancel()
}

/**
 * 운영용 [DwellScheduler] — 주입된 [scope] 위에서 코루틴 `delay` 로 지연 발화한다.
 *
 * ⚠️ [scope] 는 [ZoneEngine] 의 호출자와 같은 디스패처(운영: `Dispatchers.Main.immediate`)를
 * 써야 한다 — 스레드 풀(`Dispatchers.Default` 등)을 넘기면 [DwellScheduler] 콜백이
 * `ingest`/`apply`/`reset` 과 다른 스레드에서 `activeZoneId` 를 동기화 없이 읽게 된다.
 */
internal class CoroutineDwellScheduler(private val scope: CoroutineScope) : DwellScheduler {
    override fun schedule(delayMs: Long, action: () -> Unit): Cancellable {
        val job = scope.launch {
            delay(delayMs)
            action()
        }
        return Cancellable { job.cancel() }
    }
}

/**
 * 좌표 스트림을 Zone 이벤트(IN/OUT/DWELL)로 바꾸는 판정 엔진.
 *
 * ⚠️ 스레드 한정: [ingest]/[apply]/[reset] 호출과 [scheduler] 가 [DwellScheduler.schedule]
 * 콜백을 실행하는 스레드는 **하나(같은 디스패처)** 여야 한다 — `zones`·`activeZoneId` 등
 * 코어 상태를 동기화 없이 건드리기 때문이다. [scheduler] 에 스레드 풀 기반 스코프를 주는
 * 기본값은 두지 않는다 — 호출자가 명시적으로 단일 디스패처 스코프를 주입해야 한다
 * (예: `CoroutineDwellScheduler(scope)` 에 `Dispatchers.Main.immediate` 기반 스코프).
 *
 * 포팅 원본: ZoneEngine.swift + UwbAreaJudge.swift.
 */
public class ZoneEngine @JvmOverloads constructor(
    private val scheduler: DwellScheduler,
    public val sampleIntervalMs: Long = 1_000,
    public val confirmCount: Int = 3,
) {

    /**
     * 이벤트 확정 훅. 고객에게 나가는 콜백은 항상 FloorSession 의 리스너를 거친다 — 이 훅은
     * 내부 호출자(SessionCoordinator/Provider) 전용이다.
     */
    internal var onEvent: ((ZoneEvent) -> Unit)? = null

    /** 진단 로그 훅. */
    internal var onLog: ((LogLevel, String) -> Unit)? = null

    public var zones: List<Zone> = emptyList()
        private set

    /** 현재 확정된 활성 존 id. `null` 이면 OUT 상태(진입 후보 카운트 중이거나 아무 존도 아님). */
    public var activeZoneId: String? = null
        private set

    private var lastJudgedAtMs: Long? = null
    private var candidateZoneId: String? = null
    private var inStreak = 0
    private var outStreak = 0
    private var dwellCancellable: Cancellable? = null

    /**
     * paramsIgnored WARN 은 존이 있는 최초 apply 에서 딱 한 번만 — [reset] 으로 풀리지
     * 않는다(iOS UwbAreaJudge 와 같은 규칙: 층 전환으로 apply 가 반복돼도 다시 말하지 않는다).
     */
    private var warnedParamsIgnored = false

    /**
     * 존 목록 교체(층 전환·폴링으로 늦게 도착). 판정 상태는 버린다 — 사라진 존의 IN 상태가
     * 남으면 이탈 이벤트가 영영 안 나온다. 존이 있으면 판정 파라미터 무력화 WARN 을 남긴다
     * (최초 1회).
     */
    public fun apply(zones: List<Zone>) {
        this.zones = zones
        reset()
        warnParamsIgnoredIfNeeded()
    }

    /**
     * 좌표 1개 투입 → 상태 갱신 + 이벤트 생성. 판단은 [sampleIntervalMs] 에 1번만 한다 —
     * 그 사이 들어온 좌표는 버린다. [confirmCount] 번 연속 같은 판정이어야 IN/OUT 확정,
     * 중간에 반대 판정이 하나 나오면 그 방향 카운터가 리셋된다.
     */
    public fun ingest(p: Position, nowMs: Long) {
        val last = lastJudgedAtMs
        if (last != null && nowMs - last < sampleIntervalMs) return
        lastJudgedAtMs = nowMs

        val hit = zones.firstOrNull { it.contains(p) }
        val active = activeZoneId?.let { id -> zones.firstOrNull { z -> z.id == id } }

        if (active != null) {
            if (hit?.id == active.id) {
                outStreak = 0 // 활성 존 안 — 이탈 카운트 리셋, 진입 카운트도 볼 것 없음
                return
            }
            outStreak += 1
            if (outStreak < confirmCount) return // 아직 미확정 — 유지
            onEvent?.invoke(ZoneEvent.Exit(active, nowMs))
            activeZoneId = null
            outStreak = 0
            cancelDwell()
            // 이탈 직후 같은 샘플로 새 존 진입 카운트를 시작한다 — 아래로 이어진다.
        }

        if (hit != null) {
            if (candidateZoneId == hit.id) {
                inStreak += 1
            } else {
                candidateZoneId = hit.id
                inStreak = 1
            }
            if (inStreak >= confirmCount) {
                activeZoneId = hit.id
                onEvent?.invoke(ZoneEvent.Enter(hit, nowMs))
                candidateZoneId = null
                inStreak = 0
                scheduleDwellIfNeeded(hit, nowMs)
            }
        } else {
            candidateZoneId = null // 밖 — 진입 후보 리셋
            inStreak = 0
        }
    }

    /** streak·후보·활성 존을 비우고 대기 중인 dwell 을 취소한다. 존 목록은 유지한다. */
    public fun reset() {
        lastJudgedAtMs = null
        candidateZoneId = null
        inStreak = 0
        outStreak = 0
        activeZoneId = null
        cancelDwell()
    }

    private fun cancelDwell() {
        dwellCancellable?.cancel()
        dwellCancellable = null
    }

    /**
     * dwellSeconds > 0 인 존만 예약한다. IN 확정 시각([enteredAtMs])부터 그 시간이 지났을
     * 때도 여전히 같은 활성 존이면 1회만 발행한다(iOS UwbAreaJudge.startDwell 과 동일 계약).
     */
    private fun scheduleDwellIfNeeded(zone: Zone, enteredAtMs: Long) {
        val dwellSeconds = zone.dwellSeconds
        if (dwellSeconds == null || dwellSeconds <= 0) return
        val delayMs = dwellSeconds.toLong() * 1_000
        val dueAtMs = enteredAtMs + delayMs
        dwellCancellable =
            scheduler.schedule(delayMs) {
                if (activeZoneId == zone.id) {
                    onEvent?.invoke(ZoneEvent.Dwell(zone, dwellSeconds.toDouble(), dueAtMs))
                }
            }
    }

    private fun warnParamsIgnoredIfNeeded() {
        if (warnedParamsIgnored || zones.isEmpty()) return
        warnedParamsIgnored = true
        onLog?.invoke(LogLevel.WARN, SdkLocalized.t("uwb.paramsIgnored", zones.size))
    }
}
