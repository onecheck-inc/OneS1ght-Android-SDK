@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package co.onecheck.ones1ght.android.runtime

import co.onecheck.ones1ght.android.model.Floor
import co.onecheck.ones1ght.android.positioning.FakeHubEngine
import co.onecheck.ones1ght.android.positioning.UwbPositioningProvider
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * 코어 ↔ 내장 provider 의 생명주기 — iOS S20(일시정지 유지) · 감사 SP-B8(수신 점검 오탐) ·
 * SP-B15(배경 전환의 EXIT·중복 ENTER) · SP-B6(=iOS S10, 구역 새로고침이 다른 층에 들어감).
 */
class LifecycleProviderTest {

    @get:Rule val server = MockWebServer()

    private val routes = Routes()

    @Before fun setUp() {
        SdkLocalized.language = "ko"
        server.dispatcher = routes
        route()
    }

    @Volatile private var zonesDelayMs: Long = 0

    private fun route() {
        routes.handler = { path ->
            when {
                path.endsWith("/auth/verify") -> json(VERIFY_OK)
                path.endsWith("/config") -> json("""{ "geo_sdk_key": "gsk_x" }""")
                path.endsWith("/plan") -> json("""{"has_plan":false}""")
                path.endsWith("/anchors") -> json("""{"anchors":[{"uwbMac":"AA:BB:00:01","x":1.0,"y":1.0,"sessionId":7}]}""")
                path.endsWith("/floor/A/zones") ->
                    json("""{"zones":[{"zone_id":"za","name":"정육","is_active":true,"polygon":[[0,0],[5,0],[5,5]]}]}""")
                        .setBodyDelay(zonesDelayMs, TimeUnit.MILLISECONDS)
                path.endsWith("/floor/B/zones") ->
                    json("""{"zones":[{"zone_id":"zb","name":"과일","is_active":true,"polygon":[[0,0],[5,0],[5,5]]}]}""")
                path.endsWith("/events/zone") -> json("""{"accepted":true,"event_id":"e","triggers":[]}""")
                else -> json("""{ "accepted_count": 1 }""")
            }
        }
    }

    private class Setup(val c: SessionCoordinator, val engine: FakeHubEngine, val provider: UwbPositioningProvider, val life: FakeAppLifecycle)

    private suspend fun TestScope.started(withFloor: Boolean = true): Setup {
        val life = FakeAppLifecycle()
        val c = makeCoordinator(server, lifecycle = life)
        c.prepare()
        c.identify("p")
        if (withFloor) c.setFloorMap(Floor("A", "A"), "b")
        val engine = FakeHubEngine()
        val provider = UwbPositioningProvider.create(engine, StandardTestDispatcher(testScheduler), clock = { BASE_MS })
        provider.license = "lic"
        c.start(provider)
        engine.current!!.onStarted()
        testScheduler.runCurrent()
        return Setup(c, engine, provider, life)
    }

    // MARK: - iOS S20 — 일시정지는 생명주기 재시작에서 유지된다

    @Test fun pauseSurvivesBackgroundAndForeground() = runTest {
        val s = started()
        s.provider.pause()
        assertTrue(s.provider.isPaused)
        s.life.background()
        eventually { !s.provider.isRunning }
        s.engine.current?.onStopped()
        testScheduler.runCurrent()
        s.life.foreground()
        eventually { s.provider.isRunning }
        assertTrue("배경·복귀 한 번에 사용자가 건 일시정지가 풀렸다", s.provider.isPaused)
        s.c.stop()
    }

    // MARK: - SP-B8 — 수신 점검은 층을 잡은 뒤부터 잰다 · 배경이면 건너뛴다

    /** 엔진이 아직 층을 찾는 중이면(SEARCHING) 7초가 지나도 E4002 를 올리지 않는다 — 그건 E3007(20초)의 몫이다. */
    @Test fun receptionCheckWaitsForTracking() = runTest {
        val s = started()
        val lines = mutableListOf<String>()
        s.c.onLog = { _, line -> lines.add(line) }
        testScheduler.advanceTimeBy(8_000)
        testScheduler.runCurrent()
        assertFalse(lines.toString(), lines.any { it.startsWith("[E4002]") })
        s.engine.current!!.onTrackingStarted(14)
        testScheduler.runCurrent()
        testScheduler.advanceTimeBy(10_000)
        testScheduler.runCurrent()
        assertTrue("층을 잡고 좌표가 안 나오면 그때는 알려야 한다: $lines", lines.any { it.startsWith("[E4002]") })
        s.c.stop()
    }

    /** 배경에 있는 동안은 재지 않는다 — 돌아온 뒤 다시 잰다(설정 앱에 다녀온 사용자에게 E4002 오탐). */
    @Test fun receptionCheckSkippedWhileInBackground() = runTest {
        val s = started()
        s.engine.current!!.onTrackingStarted(14)
        testScheduler.runCurrent()
        val lines = mutableListOf<String>()
        s.c.onLog = { _, line -> lines.add(line) }
        testScheduler.advanceTimeBy(2_000)
        s.life.background()
        testScheduler.runCurrent()
        testScheduler.advanceTimeBy(10_000)
        testScheduler.runCurrent()
        assertFalse("배경에서 E4002 를 올렸다: $lines", lines.any { it.startsWith("[E4002]") })
        s.c.stop()
    }

    // MARK: - SP-B15 — 배경 전환은 EXIT, 복귀 후 ENTER 가 두 번 이어지지 않는다

    @Test fun backgroundSendsExitSoResumeIsNotDuplicateEnter() = runTest {
        val s = started()
        s.engine.current!!.onTrackingStarted(14)
        s.engine.current!!.onAreaEvent(14, "정육", "IN")
        testScheduler.runCurrent()
        eventually { routes.count("/events/zone") == 1 }
        s.life.background()
        eventually { !s.provider.isRunning }
        eventually { routes.count("/events/zone") == 2 } // EXIT 가 서버에 닿은 뒤 복귀(전송은 비동기라 순서를 고정한다)
        s.engine.current?.onStopped()
        testScheduler.runCurrent()
        s.life.foreground()
        eventually { s.provider.isRunning }
        s.engine.current!!.onStarted()
        s.engine.current!!.onTrackingStarted(14)
        s.engine.current!!.onAreaEvent(14, "정육", "IN")
        testScheduler.runCurrent()
        eventually { routes.count("/events/zone") >= 3 }
        val statuses = routes.requests.filter { it.path.endsWith("/events/zone") }
            .map { Regex("\"status\":\"(\\w+)\"").find(it.body)!!.groupValues[1] }
        assertEquals("서버에는 IN·OUT·IN 순서여야 한다(IN 두 번 연속 금지)", listOf("IN", "OUT", "IN"), statuses)
        s.c.stop()
    }

    // MARK: - SP-B6 · iOS S10 — 새로고침 결과가 다른 층에 들어가지 않는다

    @Test fun refreshZonesResultDiscardedWhenFloorChangedMeanwhile() = runTest {
        val life = FakeAppLifecycle()
        val c = makeCoordinator(server, lifecycle = life)
        c.prepare()
        c.setFloorMap(Floor("A", "A"), "b")
        zonesDelayMs = 400
        val pending = backgroundScope.async { c.refreshZones() }
        eventually { routes.count("/floor/A/zones") >= 2 }
        zonesDelayMs = 0
        c.setFloorMap(Floor("B", "B"), "b")
        eventually { pending.isCompleted }
        assertEquals("B 층에 A 층 구역이 들어갔다", listOf("zb"), c.floorState?.zones?.map { it.id })
    }
}
