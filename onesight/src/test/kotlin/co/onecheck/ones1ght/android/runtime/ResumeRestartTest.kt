@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package co.onecheck.ones1ght.android.runtime

//
//  ResumeRestartTest.kt
//  재개(FloorSession.resume → SessionCoordinator.resumeActiveProvider)가 죽은 엔진을 다시 켜는가(2026-10-19).
//
//  화면에 있는 동안 엔진의 UWB 세션이 죽으면(오류 5 · E4001 뒤 좌표 없음) 예전 재개는 일시정지 깃발만 내려,
//  일시정지 → 재개로는 측위가 돌아오지 않았다. 여기서 지키는 것: 살아 있는 엔진은 건드리지 않고, 세션 오류·좌표
//  끊김(5초)이면 한 번만 다시 켜고, 앱이 화면에 없으면 켜지 않는다. 세션·방문 ID 는 그대로다.
//
//  iOS 와 같은 규칙이다(같은 날 iOS 에도 들어간다).
//

import co.onecheck.ones1ght.android.model.Floor
import co.onecheck.ones1ght.android.positioning.FakeHubEngine
import co.onecheck.ones1ght.android.positioning.UwbPositioningProvider
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

class ResumeRestartTest {

    @get:Rule val server = MockWebServer()

    private val routes = Routes()

    /** provider 의 시계 — 좌표 끊김(5초)을 테스트가 직접 넘긴다. */
    private var now = BASE_MS
    private var active = true

    @Before fun setUp() {
        SdkLocalized.language = "ko"
        now = BASE_MS
        active = true
        server.dispatcher = routes
        routes.handler = { path ->
            when {
                path.endsWith("/auth/verify") -> json(VERIFY_OK)
                path.endsWith("/config") -> json("""{ "geo_sdk_key": "gsk_x" }""")
                path.endsWith("/plan") -> json("""{"has_plan":false}""")
                path.endsWith("/anchors") -> json("""{"anchors":[{"uwbMac":"AA:BB:00:01","x":1.0,"y":1.0,"sessionId":7}]}""")
                path.endsWith("/zones") -> json("""{"zones":[]}""")
                else -> json("""{ "accepted_count": 1 }""")
            }
        }
    }

    private class Setup(val c: SessionCoordinator, val engine: FakeHubEngine, val provider: UwbPositioningProvider)

    /** 가동 → 엔진 시작 → 층 추적 → 좌표 한 번까지(살아 있는 엔진). */
    private suspend fun TestScope.tracking(): Setup {
        val c = makeCoordinator(server, lifecycle = FakeAppLifecycle())
        c.isAppActive = { active }
        c.prepare()
        c.identify("p")
        c.setFloorMap(Floor("A", "A"), "b")
        val engine = FakeHubEngine()
        val provider = UwbPositioningProvider.create(engine, StandardTestDispatcher(testScheduler), clock = { now })
        provider.license = "lic"
        c.start(provider)
        engine.current!!.onStarted()
        engine.current!!.onTrackingStarted(14)
        engine.current!!.onPosition(14, 1.0, 1.0, 0.0)
        testScheduler.runCurrent()
        assertEquals(1, engine.starts)
        return Setup(c, engine, provider)
    }

    private val restartLine get() = SdkLocalized.t("uwb.resumeRestart")

    private fun Setup.restartLines(): Int = provider.log.count { it.contains(restartLine) }

    /** 살아 있는 엔진(좌표가 5초 안에 있었다) — 다시 켜지 않는다. 예전 그대로 일시정지만 푼다. */
    @Test fun healthyResumeDoesNotRestart() = runTest {
        val s = tracking()
        s.provider.pause()
        now += 2_000
        s.engine.current!!.onPosition(14, 1.5, 1.0, 0.0) // 일시정지 중에도 엔진은 좌표를 준다
        testScheduler.runCurrent()
        now += UwbPositioningProvider.RESUME_STALE_FIX_MS // 마지막 좌표 뒤 딱 5초 — 아직 끊김이 아니다

        s.c.resumeActiveProvider()
        testScheduler.runCurrent()

        assertFalse(s.provider.isPaused)
        assertEquals("살아 있는 엔진을 끄면 안 된다", 0, s.engine.stops)
        assertEquals(1, s.engine.starts)
        assertEquals(0, s.restartLines())
        s.c.stop()
    }

    /** 마지막 좌표 뒤 세션 오류(5) — 한 번 다시 켠다. 세션·방문 ID 는 그대로고, 겹쳐 불러도 두 번 켜지 않는다. */
    @Test fun resumeAfterSessionErrorRestartsOnce() = runTest {
        val s = tracking()
        val visitor = s.c.visitorId
        s.engine.current!!.onError(5, "dl-tdoa session failed")
        testScheduler.runCurrent()
        s.provider.pause()

        s.c.resumeActiveProvider()
        testScheduler.runCurrent()
        assertEquals("엔진을 내려야 한다", 1, s.engine.stops)
        s.c.resumeActiveProvider() // 내려가는 중에 또 — 아무것도 안 한다
        testScheduler.runCurrent()
        assertEquals(1, s.engine.stops)

        s.engine.current!!.onStopped() // 정지가 끝나면 예약된 시작을 이어받는다
        testScheduler.runCurrent()
        assertEquals("다시 한 번만 켠다", 2, s.engine.starts)
        s.c.resumeActiveProvider() // 새로 연 엔진 — 다시 켜지 않는다
        testScheduler.runCurrent()

        assertEquals(1, s.engine.stops)
        assertEquals(2, s.engine.starts)
        assertTrue(s.provider.isRunning)
        assertFalse("재개했으니 일시정지는 풀려 있어야 한다", s.provider.isPaused)
        assertTrue(s.c.isRunning)
        assertEquals("세션은 그대로다", visitor, s.c.visitorId)
        assertEquals("정보 로그는 한 줄", 1, s.restartLines())
        s.c.stop()
    }

    /** 좌표가 5초 넘게 없다 — 다시 켠다. */
    @Test fun resumeAfterStaleFixRestarts() = runTest {
        val s = tracking()
        s.provider.pause()
        now += UwbPositioningProvider.RESUME_STALE_FIX_MS + 1

        s.c.resumeActiveProvider()
        testScheduler.runCurrent()
        assertEquals(1, s.engine.stops)
        s.engine.current!!.onStopped()
        testScheduler.runCurrent()

        assertEquals(2, s.engine.starts)
        assertTrue(s.provider.isRunning)
        assertFalse(s.provider.isPaused)
        assertEquals(1, s.restartLines())
        s.c.stop()
    }

    /** 앱이 화면에 없으면 엔진이 죽어 있어도 켜지 않는다(엔진은 포그라운드 전용) — 일시정지만 푼다. */
    @Test fun resumeWhileNotForegroundDoesNotRestart() = runTest {
        val s = tracking()
        s.engine.current!!.onError(5, "dl-tdoa session failed")
        testScheduler.runCurrent()
        s.provider.pause()
        now += UwbPositioningProvider.RESUME_STALE_FIX_MS + 1
        active = false

        s.c.resumeActiveProvider()
        testScheduler.runCurrent()

        assertFalse(s.provider.isPaused)
        assertEquals(0, s.engine.stops)
        assertEquals(1, s.engine.starts)
        assertEquals(0, s.restartLines())
        s.c.stop()
    }
}
