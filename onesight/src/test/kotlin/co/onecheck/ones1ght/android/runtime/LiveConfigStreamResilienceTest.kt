package co.onecheck.ones1ght.android.runtime

import co.onecheck.ones1ght.android.model.ConfigChange
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 실시간 연결의 견고성 — 감사 SP-B12(백오프·콜백 예외) · iOS #54(SDK 가 끊은 연결은 WARN 아님) · iOS S27(현지화).
 */
class LiveConfigStreamResilienceTest {

    @get:Rule val server = MockWebServer()

    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "lcs-resilience") }
    private val scope = CoroutineScope(executor.asCoroutineDispatcher() + SupervisorJob())
    private val logs: MutableList<Pair<LogLevel, String>> = Collections.synchronizedList(mutableListOf())

    @Before fun setUp() {
        SdkLocalized.language = "ko"
    }

    @After fun tearDown() {
        scope.cancel()
        executor.shutdownNow()
        SdkLocalized.language = null
    }

    private fun stream(
        onChange: (ConfigChange) -> Unit = {},
        delayFn: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) },
        clock: () -> Long = System::currentTimeMillis,
    ) = LiveConfigStream(
        http = OkHttpClient(),
        baseUrl = server.url("/api/sdk/v1").toString().trimEnd('/'),
        apiKey = "ock_test",
        scope = scope,
        onChange = onChange,
        log = { level, line -> logs += level to line },
        random = { 0.5 }, // 흔들기 없음(×1.0)
        delayFn = delayFn,
        clock = clock,
    )

    /**
     * 붙자마자 끊기는 프록시 — 예전엔 "붙었다" 는 이유로 백오프가 매번 1초로 돌아가 1초마다 재연결 + ResyncNeeded
     * 폭주였다. 이제 연결이 일정 시간 유지됐을 때만 백오프를 되돌린다.
     */
    @Test fun backoffGrowsWhenConnectionDropsRightAway() {
        repeat(6) { server.enqueue(MockResponse().setBody("")) } // 200 + 곧바로 EOF
        val delays = Collections.synchronizedList(mutableListOf<Long>())
        val done = CountDownLatch(4)
        val s = stream(delayFn = { ms ->
            delays += ms
            done.countDown()
            if (done.count == 0L) kotlinx.coroutines.awaitCancellation()
        })
        s.start(null, null)
        assertTrue("재연결 대기가 4번 안 왔다", done.await(5, TimeUnit.SECONDS))
        s.stop()
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L), delays.take(4))
    }

    /** 앱의 onConfigChanged 가 던져도 스트림(과 앱)이 죽지 않는다 — 로그만 남기고 다음 신호를 계속 받는다. */
    @Test fun throwingCallbackDoesNotKillStream() {
        val got = mutableListOf<ConfigChange>()
        val s = stream(onChange = { change ->
            got += change
            if (change is ConfigChange.ResyncNeeded) throw IllegalStateException("app bug")
        })
        s.onConnected() // ResyncNeeded → 앱 콜백이 던진다
        s.ingest(SseFrame(event = "zones.changed", data = """{"seq":1,"floor_id":"14"}"""))
        assertEquals(2, got.size)
        assertTrue(logs.toString(), logs.any { it.first == LogLevel.WARN })
    }

    /** SDK 가 끊은 연결(층 전환·배경·정지)은 「끊김」 WARN 이 아니다 — 진짜 끊김과 섞였다(iOS #54). */
    @Test fun selfCancelledConnectionIsNotWarned() {
        server.enqueue(MockResponse().setBody("event:hello\ndata:{\"seq\":1}\n\n").setBodyDelay(10, TimeUnit.SECONDS))
        val connected = CountDownLatch(1)
        val s = stream(onChange = { if (it is ConfigChange.ResyncNeeded) connected.countDown() })
        s.start(null, null)
        assertTrue(connected.await(5, TimeUnit.SECONDS))
        s.stop()
        // 고정 대기 대신 수신 코루틴이 끝나기를 기다린다(감사 SF-T1) — 끝났으면 끊김 로그를 남길 기회도 지났다.
        awaitNoActiveChildren(scope)
        assertFalse(logs.toString(), logs.any { it.first == LogLevel.WARN })
    }

    /** 실시간 연결 로그도 설정한 언어로 나온다(예전엔 한국어 고정 — iOS S27). */
    @Test fun logsFollowSdkLanguage() {
        SdkLocalized.language = "en"
        val s = stream()
        s.onConnected()
        s.ingest(SseFrame(event = "zones.changed", data = """{"seq":5}"""))
        s.ingest(SseFrame(event = "zones.changed", data = """{"seq":9}""")) // 갭
        val hangul = Regex("[가-힣]")
        assertTrue(logs.toString(), logs.isNotEmpty())
        assertFalse(logs.toString(), logs.any { hangul.containsMatchIn(it.second) })
    }
}
