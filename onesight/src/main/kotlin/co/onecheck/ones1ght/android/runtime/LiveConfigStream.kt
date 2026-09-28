package co.onecheck.ones1ght.android.runtime

//
//  LiveConfigStream.kt
//  콘솔 변경 실시간 수신 (SSE).
//
//  · 연결이 될 때마다 ResyncNeeded 를 올린다 — 백그라운드 등으로 연결이 끊겼다가 다시
//    붙으면 "그 사이를 놓쳤을 수 있다"는 뜻이다(연결 유지·재연결 트리거는 이 클래스
//    바깥, 다른 태스크의 몫이다).
//  · ⚠️ 받은 신호로 무엇을 할지는 고객사가 정한다. 이 클래스는 존을 다시 받지 않고,
//    신호를 접지도 않는다. 디바운스가 필요하면 고객사가 건다.
//  · ⚠️ `BufferedSource.readUtf8Line()` 이 돌려주는 **빈 줄도 파서에 그대로 넘긴다** —
//    SSE 는 빈 줄로 프레임이 끝난다. 이걸 건너뛰면(예: `bytes.lines()` 류의 API가 빈 줄을
//    삼키는 경우) 연결도 되고 하트비트도 받는데 이벤트만 조용히 사라지는, iOS 에서 실제로
//    겪은 사고(2026-08-25)와 같은 증상이 난다.
//
//  포팅 원본: LiveConfigStream.swift.
//

import co.onecheck.ones1ght.android.model.ConfigChange
import co.onecheck.ones1ght.android.model.parse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.BufferedSource
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.math.min

internal class LiveConfigStream(
    private val http: OkHttpClient,
    private val baseUrl: String,
    private val apiKey: String,
    private val scope: CoroutineScope,
    private val onChange: (ConfigChange) -> Unit,
    private val log: (LogLevel, String) -> Unit,
    private val random: () -> Double = Math::random,
    private val delayFn: suspend (Long) -> Unit = { delay(it) },
) {

    /**
     * [http] 는 10초 읽기 타임아웃이 걸려 있다(요청/응답형 호출용) — 스트림은 응답이 계속
     * 이어지므로 그대로 쓸 수 없다. `newBuilder()` 로 스트리밍 전용 타임아웃만 새로 만든다.
     * 원본(=ApiClient 가 들고 있는 공용 클라이언트)은 절대 건드리지 않는다.
     */
    private val streamHttp: OkHttpClient = http.newBuilder()
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.SECONDS)
        .build()

    @Volatile private var job: Job? = null

    @Volatile private var currentCall: Call? = null

    private var lastSeq: Int? = null

    private val minBackoffMs = 1_000L
    private val maxBackoffMs = 30_000L

    // MARK: - 수명주기

    fun start(buildingId: String?, floorId: String?) {
        stop()
        lastSeq = null
        job = scope.launch {
            var backoff = minBackoffMs
            while (isActive) {
                val connected = consume(buildingId, floorId)
                if (!isActive) break
                if (connected) backoff = minBackoffMs
                delayFn(jitter(backoff))
                backoff = min(backoff * 2, maxBackoffMs)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        currentCall?.cancel()
        currentCall = null
    }

    // MARK: - 수신

    /** 한 번 붙어서 끊길 때까지 읽는다. 반환값 = 실제로 붙었는가(백오프 초기화 판단용). */
    private suspend fun consume(buildingId: String?, floorId: String?): Boolean {
        val call = streamHttp.newCall(buildRequest(buildingId, floorId))
        currentCall = call
        // 읽기는 IO 스레드에서 막히며 돈다. 그 안에서 콜백(onChange·log)을 바로 부르면 고객
        // 콜백(onConfigChanged·onDebugLog)이 IO 스레드에서 불리고 lastSeq 가 두 스레드에서
        // 바뀐다 — 받은 것은 전부 [scope] 로 넘겨 거기서 처리한다(도착 순서 그대로).
        // 스트림 작업(job)의 자식으로 띄워 stop() 뒤에는 남은 것이 전달되지 않게 한다.
        val streamJob = currentCoroutineContext()[Job]
        val post: (() -> Unit) -> Unit = { block ->
            scope.launch(streamJob ?: EmptyCoroutineContext) { block() }
        }
        return try {
            withContext(Dispatchers.IO) {
                call.execute().use { response ->
                    if (!response.isSuccessful) {
                        val code = response.code
                        post { log(LogLevel.WARN, "live: 연결 거절 $code") }
                        false
                    } else {
                        post { onConnected() }
                        response.body?.source()?.let { readFrames(it, post) }
                        true
                    }
                }
            }
        } catch (e: IOException) {
            // stop() 이 진행 중이던 call 을 취소해도 여기로 떨어진다 — 정상 종료 경로다.
            log(LogLevel.WARN, "live: 끊김 $e")
            false
        } finally {
            currentCall = null
        }
    }

    private fun buildRequest(buildingId: String?, floorId: String?): Request {
        val urlBuilder = baseUrl.trimEnd('/').toHttpUrl().newBuilder().addPathSegment("stream")
        buildingId?.let { urlBuilder.addQueryParameter("buildingId", it) }
        floorId?.let { urlBuilder.addQueryParameter("floorId", it) }
        return Request.Builder()
            .url(urlBuilder.build())
            .header("X-SDK-Key", apiKey)
            .header("Accept", "text/event-stream")
            .build()
    }

    /** EOF 까지 줄 단위로 읽어 파서에 먹인다 — 완성된 프레임마다 [ingest]. */
    private fun readFrames(source: BufferedSource, post: (() -> Unit) -> Unit) {
        val parser = SseFrameParser()
        while (true) {
            val line = source.readUtf8Line() ?: return
            parser.feedLine(line)?.let { frame -> post { ingest(frame) } }
        }
    }

    /** 프레임 1건 처리 — 갭 판정 후 고객사 통지. 테스트에서 직접 부른다(네트워크 없이). */
    internal fun ingest(frame: SseFrame) {
        val signal = ConfigChange.parse(frame.event, frame.data) ?: return
        val (seq, change) = signal
        if (seq != null) {
            val last = lastSeq
            if (last != null && seq != last + 1) {
                log(LogLevel.WARN, "live: 일련번호 갭 $last → $seq, 재동기화 요청")
                onChange(ConfigChange.ResyncNeeded)
            }
            lastSeq = seq
        }
        if (change != null) onChange(change)
    }

    /** 연결이 (재)수립됐다 — 기준선을 새로 잡고 재동기화를 알린다. */
    internal fun onConnected() {
        log(LogLevel.INFO, "live: 연결됨")
        lastSeq = null
        onChange(ConfigChange.ResyncNeeded)
    }

    /** ±20% 흔들어 재연결이 한꺼번에 몰리지 않게 한다. */
    private fun jitter(ms: Long): Long = (ms * (0.8 + random() * 0.4)).toLong()
}
