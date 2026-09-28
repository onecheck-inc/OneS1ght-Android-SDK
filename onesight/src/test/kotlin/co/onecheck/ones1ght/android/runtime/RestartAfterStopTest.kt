@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package co.onecheck.ones1ght.android.runtime

//
//  RestartAfterStopTest.kt
//  종료 직후의 재시작 — 그 사이에 낀 start 가 삼켜지지 않는가.
//
//  2026-09-10 실기기(iOS): `stop()` 은 잔여 좌표를 서버로 flush 하느라 기다리고,
//  `isRunning = false` 는 그 왕복이 끝난 뒤에야 세운다. 그 창에 들어온 `start()` 는
//  `!isRunning` 가드에 걸려 로그 한 줄 없이 돌아갔다.
//
//  포팅 원본: RestartAfterStopTests.swift.
//

import co.onecheck.ones1ght.android.model.Coordinates
import co.onecheck.ones1ght.android.positioning.MockPositioningProvider
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.TimeUnit

class RestartAfterStopTest {

    @get:Rule val server = MockWebServer()

    private val routes = Routes()
    private lateinit var p: MockPositioningProvider

    @Before
    fun setUp() {
        server.dispatcher = routes
        p = MockPositioningProvider()
        routeWithSlowFlush()
    }

    /** 좌표·로그 전송만 느리게 만든다 — `stop()` 이 실기기처럼 flush 에서 실제로 매달리게. */
    private fun routeWithSlowFlush(uploadDelayMs: Long = 150) {
        routes.handler = { path ->
            when {
                path.endsWith("/auth/verify") -> json(VERIFY_OK)
                path.endsWith("/config") -> json("{}")
                path.endsWith("/logs") ->
                    json("""{ "accepted_count": 1 }""").setBodyDelay(uploadDelayMs, TimeUnit.MILLISECONDS)
                else -> json("{}")
            }
        }
    }

    private suspend fun TestScope.prepared(): SessionCoordinator {
        val c = makeCoordinator(server, flushThreshold = 1_000) // 자동 전송에 안 걸리게
        c.prepare()
        c.identify("p")
        return c
    }

    /**
     * ⚠️ 이 테스트가 핵심이다 — 실기기에서 났던 그 순서 그대로다. 종료를 띄우고, 종료가
     * flush 에 매달려 있는 그 순간에 새 시작이 들어온다.
     */
    @Test fun startDuringStopEndsRunning() = runTest {
        val c = prepared()
        c.start(p)
        // 보낼 좌표를 쌓아 둔다 — 버퍼가 비어 있으면 flush 가 왕복 없이 즉시 끝나 재현이 안 된다.
        p.simulatePosition(Coordinates(1.0, 2.0, 0.0), "F", BASE_MS)

        val stopJob = launch { c.stop() }
        runCurrent()
        assertTrue("정지가 flush 에 매달려 있어야 재현된다", c.isRunning)

        c.start(p)
        stopJob.join()
        advanceUntilIdle()

        assertTrue("종료 직후의 start 가 삼켜졌다 — 화면은 「찾는 중」인데 실제로는 아무것도 안 돈다", c.isRunning)
        assertTrue("코어까지 다시 떠야 좌표가 나온다", p.isRunning)
    }

    /**
     * 정지가 flush 에 매달린 사이 앱이 전경으로 돌아와도 측위를 다시 켜지 않는다 — 켜면 정지가
     * 끝난 뒤 isRunning=false 인데 provider 만 도는 유령 세션이 남는다. 배경 전환도 마찬가지로
     * 진행 중인 정지에 끼어들지 않는다.
     */
    @Test fun lifecycleEventsDuringStopDoNotRevivePositioning() = runTest {
        val lifecycle = FakeAppLifecycle()
        val c = makeCoordinator(server, lifecycle = lifecycle, flushThreshold = 1_000)
        c.prepare()
        c.identify("p")
        c.start(p)
        p.simulatePosition(Coordinates(1.0, 2.0, 0.0), "F", BASE_MS)
        val onForeground = lifecycle.onForeground!!
        val onBackground = lifecycle.onBackground!!

        val stopJob = launch { c.stop() }
        runCurrent()
        assertTrue("정지가 flush 에 매달려 있어야 재현된다", c.isRunning)
        assertFalse(p.isRunning)

        onBackground()
        runCurrent()
        onForeground()
        runCurrent()
        assertFalse("진행 중인 정지 사이에 provider 가 다시 켜졌다", p.isRunning)

        stopJob.join()
        advanceUntilIdle()
        assertFalse(c.isRunning)
        assertFalse("정지가 끝났는데 provider 가 돌고 있다", p.isRunning)
        assertEquals("잔여 좌표는 정지가 한 번만 올린다", 1, routes.count("/positioning/logs"))
    }

    /** 정지가 끝난 뒤의 start 는 당연히 뜬다(회귀 대조군). */
    @Test fun startAfterStopCompletesRuns() = runTest {
        val c = prepared()
        c.start(p)
        p.simulatePosition(Coordinates(1.0, 2.0, 0.0), "F", BASE_MS)

        c.stop()
        assertFalse(c.isRunning)
        c.start(p)
        assertTrue(c.isRunning)
        assertTrue(p.isRunning)
    }

    /** 종료를 두 번 불러도 한 번만 내려간다 — 뒤엣것은 앞엣것에 합류한다. */
    @Test fun concurrentStopsUploadOnce() = runTest {
        val c = prepared()
        c.start(p)
        p.simulatePosition(Coordinates(1.0, 2.0, 0.0), "F", BASE_MS)
        p.simulatePosition(Coordinates(2.0, 2.0, 0.0), "F", BASE_MS + 1_000)
        p.simulatePosition(Coordinates(3.0, 2.0, 0.0), "F", BASE_MS + 2_000)
        val lines = mutableListOf<String>()
        c.onLog = { _, line -> lines.add(line) }

        val a = launch { c.stop() }
        val b = launch { c.stop() }
        a.join()
        b.join()

        assertFalse(c.isRunning)
        assertEquals("같은 잔여 좌표를 두 번 올렸다", 1, routes.count("/positioning/logs"))
        // 뒤엣것이 합류하지 않고 따로 내려가면, 앞엣것이 flush 중인 좌표를 "유실" 로 잘못 남긴다.
        assertEquals("정지는 한 번만 일어난다: $lines", 1, lines.count { it.startsWith("[I4002]") })
        assertFalse("전송 중인 좌표를 유실로 치면 안 된다: $lines", lines.any { it.startsWith("[E5006]") })
    }

    /** 이미 돌고 있는데 또 start 하면 멱등이다 — 다만 **말은 한다**. */
    @Test fun duplicateStartKeepsVisitorId() = runTest {
        val c = prepared()
        c.start(p)
        val visitorBefore = c.visitorId
        val lines = mutableListOf<Pair<LogLevel, String>>()
        c.onLog = { level, line -> lines.add(level to line) }

        c.start(p)

        assertTrue(c.isRunning)
        assertEquals("멱등이어야 한다 — 방문이 새로 발급되면 안 된다", visitorBefore, c.visitorId)
        assertTrue(lines.toString(), lines.contains(LogLevel.WARN to SdkLocalized.t("coord.startIgnored")))
    }
}
