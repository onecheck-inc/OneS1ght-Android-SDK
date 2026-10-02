package co.onecheck.ones1ght.android.runtime

import co.onecheck.ones1ght.android.model.Coordinates
import co.onecheck.ones1ght.android.model.PositionPoint
import co.onecheck.ones1ght.android.positioning.MockPositioningProvider
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * 좌표·로그 전송 — iOS S1 · S5 · S13(=SP-B7) · S4(=SF-A6·SP-B11) · iOS #54(E3001 은 서버로 안 올린다).
 */
class UploadPipelineTest {

    @get:Rule val server = MockWebServer()

    private val routes = Routes()
    private val provider = MockPositioningProvider()

    @Before fun setUp() {
        server.dispatcher = routes
    }

    private fun route(positionsOffline: Boolean = false, configStatus: Int = 200) {
        routes.handler = { path ->
            when {
                path.endsWith("/auth/verify") -> json(VERIFY_OK)
                path.endsWith("/config") -> json("{}", configStatus)
                path.endsWith("/positioning/logs") ->
                    if (positionsOffline) MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START)
                    else json("""{ "accepted_count": 1 }""")
                path.endsWith("/logs") -> json("""{ "accepted_count": 1 }""")
                path.contains("/positioning/floors/") -> json("""{ "detail": "x" }""", 500)
                else -> json("{}")
            }
        }
    }

    private fun point(i: Int) = PositionPoint("F", Coordinates(i.toDouble(), 0.0, 0.0), "t$i")

    // MARK: - S1 — 전송 중 empty()

    /** 전송이 끝나기 전에 앱이 empty() 해도 죽지 않는다(예전: 빈 리스트에서 removeAt(0) → IndexOutOfBounds). */
    @Test fun emptyDuringSendDoesNotCrash() = runTest {
        val buffer = TrajectoryBuffer(maxBatch = 10)
        (1..5).forEach { buffer.append(point(it)) }
        buffer.flush { _ ->
            buffer.clear()
            true
        }
        assertEquals(0, buffer.count)
    }

    /** 전송 중 비우고 새 좌표가 들어오면, 끝난 전송이 **새 좌표를** 지우지 않는다. */
    @Test fun emptyDuringSendKeepsPointsAppendedAfterwards() = runTest {
        val buffer = TrajectoryBuffer(maxBatch = 10)
        (1..5).forEach { buffer.append(point(it)) }
        val sent = mutableListOf<Int>()
        buffer.flush { batch ->
            sent += batch.size
            if (sent.size == 1) {
                buffer.clear()
                buffer.append(point(100))
                buffer.append(point(101))
                false // 새 좌표는 이번 flush 에서 보내지 않고 남긴다
            } else {
                true
            }
        }
        assertEquals(2, buffer.count)
    }

    // MARK: - S5 — 오프라인 폭주

    /**
     * 임계값을 넘긴 뒤 오프라인이면 좌표마다 전송을 다시 시도하지 않는다 — 실패 뒤엔 기다린다. 예전엔 300건을
     * 넘은 뒤 좌표(4Hz)마다 전송 → 실패 → E5001(ERROR) → 로그 전송까지 초당 최대 8요청이었다.
     */
    @Test fun offlineDoesNotRetryOnEveryPosition() = runTest {
        route(positionsOffline = true)
        val c = makeCoordinator(server, flushThreshold = 3)
        // 전송 실패 한 건 = 화면 로그의 「[E5xxx] … — positions=N」 한 줄(오프라인 흉내가 E5001·E5005 중 무엇으로 끝나든).
        val failures = java.util.concurrent.atomic.AtomicInteger()
        c.onLog = { _, line -> if (line.startsWith("[E5") && "positions=" in line) failures.incrementAndGet() }
        c.prepare()
        c.identify("p")
        c.start(provider)
        fun position(i: Int) {
            provider.simulatePosition(Coordinates(i.toDouble(), 0.0, 0.0), "F", BASE_MS + i * 1_000L)
            testScheduler.runCurrent()
        }
        // 고정 대기(Thread.sleep) 대신 조건 대기(감사 SF-T1 · K16): 임계(3건)에서 나간 첫 전송이 실패로 끝날 때까지 본다.
        for (i in 0 until 3) position(i)
        eventually(message = "첫 전송 실패가 처리되지 않았다") { failures.get() == 1 }
        // 실패 뒤 좌표가 계속 와도(가상 시계는 그대로 — backoff 창 안) 새 전송을 걸지 않는다.
        for (i in 3 until 40) position(i)
        // 서버가 본 전송은 전부 실패 처리까지 끝났다 — 그 뒤로는 더 걸릴 전송이 없다.
        eventually(message = "전송 처리가 끝나지 않았다") { failures.get() == routes.count("/positioning/logs") }
        val attempts = routes.count("/positioning/logs")
        assertEquals("오프라인에서 좌표마다 재시도했다: $attempts", 1, attempts)
        assertEquals(40, c.pendingCount)
    }

    // MARK: - S13 · SP-B7 — 프로필 전 로그

    /**
     * 프로필이 생기기 전의 로그(대표: 초기화 중 E1007)를 버리지 않고 붙들었다가 identify 때 보낸다. 예전엔 버퍼에서
     * 떼어 낸 뒤 귀속할 곳이 없다고 버려, 가장 중요한 E1007 이 콘솔에 안 올라갔다.
     */
    @Test fun logsBeforeIdentifyAreHeldAndSentOnIdentify() = runTest {
        route(configStatus = 500)
        val c = makeCoordinator(server)
        c.prepare() // /config 실패 → E1007(ERROR) — 즉시 flush 시도
        never(200) { routes.count("/api/sdk/v1/logs") > 0 }
        c.identify("p-1")
        eventually { routes.requests.any { it.path == "/api/sdk/v1/logs" } }
        val body = routes.requests.first { it.path == "/api/sdk/v1/logs" }.body
        assertTrue(body, body.contains("\"E1007\""))
        assertTrue(body, body.contains("\"p-1\""))
    }

    // MARK: - S4 · SF-A6 · SP-B11 — 죽은 층 설정 조회

    /** 좌표마다 층 설정을 서버에 다시 묻지 않는다(결과를 아무도 안 읽었다 — 오프라인·5xx 면 4Hz 폭주). */
    @Test fun noFloorConfigRequests() = runTest {
        route()
        val c = makeCoordinator(server, flushThreshold = 1_000)
        c.prepare()
        c.identify("p")
        c.start(provider)
        for (i in 0 until 10) provider.simulatePosition(Coordinates(1.0, 1.0, 0.0), "F", BASE_MS + i * 1_000L)
        provider.simulateZone("Z1", co.onecheck.ones1ght.android.model.ZoneEventStatus.ENTER, "F", BASE_MS)
        never(200) { routes.requests.any { it.path.contains("/positioning/floors/") } }
        c.stop()
    }

    // MARK: - iOS #54 — 층 없이 begin 은 정상

    /** 층 없이 시작하면 화면 로그(INFO)만 — E3001 을 서버로 올리지 않는다(엔진이 BLE 로 층을 찾는 정상 경로). */
    @Test fun startWithoutFloorLogsInfoOnlyNoE3001() = runTest {
        route()
        val c = makeCoordinator(server)
        c.prepare()
        c.identify("p")
        val lines = mutableListOf<Pair<LogLevel, String>>()
        c.onLog = { level, line -> lines.add(level to line) }
        c.start(provider)
        assertTrue(lines.toString(), lines.any { it.first == LogLevel.INFO && it.second == SdkLocalized.t("coord.noFloorLoaded") })
        assertFalse(lines.toString(), lines.any { it.second.startsWith("[E3001]") })
        c.stop()
        assertFalse(routes.requests.filter { it.path == "/api/sdk/v1/logs" }.any { it.body.contains("E3001") })
    }
}
