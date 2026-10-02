@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package co.onecheck.ones1ght.android.runtime

//
//  EngineUnexpectedStopTest.kt
//  엔진이 스스로 꺼졌을 때 — 세션이 "측위 중" 인 채 굳지 않는가(감사 SP-B1).
//
//  엔진이 스스로 멈추면(오류 3·7·10, Bluetooth 꺼짐 E2004 등) provider 만 IDLE 이 되고 코어 isRunning 은
//  true 로 남았다. 그러면 안내대로 begin() 해도 coord.startIgnored 로 삼켜져 앱을 껐다 켜기 전엔 측위가
//  안 돌아왔다. 포팅 원본: EngineUnexpectedStopTests.swift (iOS #54).
//

import co.onecheck.ones1ght.android.model.Coordinates
import co.onecheck.ones1ght.android.positioning.MockPositioningProvider
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class EngineUnexpectedStopTest {

    @get:Rule val server = MockWebServer()

    private val fx = CoordinatorFixture(server) // 기본 응답(verify·logs·그 밖 {})이 이 테스트에 맞는다(감사 K16)
    private lateinit var p: MockPositioningProvider
    private val lines get() = fx.lines

    @Before
    fun setUp() {
        p = MockPositioningProvider()
    }

    private suspend fun TestScope.started(
        lifecycle: AppLifecycle? = null,
        delays: List<Long> = listOf(3_000L, 10_000L),
    ): SessionCoordinator = with(fx) { started(p, lifecycle = lifecycle, flushThreshold = 1_000, engineRestartDelaysMs = delays) }

    private fun e4001(): List<String> = fx.codeLines("E4001")

    /** 다시 켜 볼 만하면 정해진 간격 뒤에 다시 켠다 — 세션은 그대로. */
    @Test fun retryableStopRestartsEngine() = runTest {
        val c = started()
        assertEquals(1, p.startCount)

        p.simulateUnexpectedStop(retryable = true)
        advanceTimeBy(2_999); runCurrent()
        assertEquals("간격 전에는 켜지 않는다", 1, p.startCount)
        advanceTimeBy(2); runCurrent()

        assertEquals("엔진을 다시 켜야 한다", 2, p.startCount)
        assertTrue(p.isRunning)
        assertTrue("다시 켜는 동안 세션은 닫지 않는다", c.isRunning)
        assertEquals(e4001().toString(), 1, e4001().size)
        assertTrue(e4001().first(), "retry 1/2 in 3s" in e4001().first())
    }

    /** 다시 켜기를 다 쓰면 세션을 닫는다 — 그 뒤 begin(start) 이 삼켜지지 않는다. */
    @Test fun exhaustedRetriesCloseSessionAndStartWorksAgain() = runTest {
        val c = started()
        p.simulateUnexpectedStop(retryable = true)
        advanceTimeBy(3_001); runCurrent()
        p.simulateUnexpectedStop(retryable = true)
        advanceTimeBy(10_001); runCurrent()
        assertEquals(3, p.startCount)
        assertTrue(c.isRunning)

        p.simulateUnexpectedStop(retryable = true) // 소진 → 닫음
        eventually { !c.isRunning }
        assertTrue(e4001().toString(), e4001().last().contains("session closed"))

        c.start(p)
        assertTrue("닫힌 뒤 start 는 다시 먹어야 한다", c.isRunning)
        assertTrue(lines.none { it.second == SdkLocalized.t("coord.startIgnored") })
    }

    /** 사람이 풀어야 하는 원인(권한·Bluetooth·라이선스)은 다시 켜 보지 않고 바로 닫는다 — E4001 은 덧붙이지 않는다. */
    @Test fun nonRetryableStopClosesImmediately() = runTest {
        val c = started()
        p.simulateUnexpectedStop(retryable = false, context = "engine=3 powered off")
        eventually { !c.isRunning }
        advanceTimeBy(60_000); runCurrent()
        assertEquals("다시 켜지 않는다", 1, p.startCount)
        assertTrue(e4001().toString(), e4001().isEmpty())
        assertTrue(lines.toString(), lines.any { it.first == LogLevel.WARN && "engine=3 powered off" in it.second })
    }

    /** 좌표가 한 번 나오면 횟수를 처음부터 센다. */
    @Test fun positionResetsRetryCount() = runTest {
        val c = started(delays = listOf(3_000L))
        p.simulateUnexpectedStop(retryable = true)
        advanceTimeBy(3_001); runCurrent()
        assertEquals(2, p.startCount)
        p.simulatePosition(Coordinates(1.0, 1.0, 0.0), "F", BASE_MS)

        p.simulateUnexpectedStop(retryable = true) // 횟수가 남아 있으므로 다시 켠다
        advanceTimeBy(3_001); runCurrent()
        assertEquals(3, p.startCount)
        assertTrue(c.isRunning)
    }

    /** 종료한 뒤에 온 알림은 무시한다. */
    @Test fun notificationAfterEndIsIgnored() = runTest {
        val c = started()
        c.stop()
        p.simulateUnexpectedStop(retryable = true)
        advanceTimeBy(60_000); runCurrent()
        assertEquals(1, p.startCount)
        assertFalse(c.isRunning)
    }

    /** 시작 안에서 동기로 접혀도(재시도 불가) 놓치지 않는다 — 세션이 닫히고 다음 start 가 먹는다. */
    @Test fun synchronousStartFailureIsNotMissed() = runTest {
        val c = makeCoordinator(server, flushThreshold = 1_000)
        c.onLog = { level, line -> lines += level to line }
        c.prepare()
        c.identify("pf")
        p.failNextStartSynchronously = false
        c.start(p)
        eventually { !c.isRunning }

        c.start(p)
        assertTrue(c.isRunning)
        assertTrue(p.isRunning)
        assertTrue(lines.none { it.second == SdkLocalized.t("coord.startIgnored") })
    }

    /** 백그라운드에서는 다시 켜지 않는다(UWB 가 안 돈다) — 포그라운드 복귀가 켜고 횟수도 초기화한다. */
    @Test fun noRestartInBackground_foregroundRestartsAndResets() = runTest {
        val lifecycle = FakeAppLifecycle()
        val c = started(lifecycle = lifecycle, delays = listOf(3_000L))
        p.simulateUnexpectedStop(retryable = true)
        lifecycle.background()
        advanceTimeBy(10_000); runCurrent()
        assertEquals("배경에서는 켜지 않는다", 1, p.startCount)
        assertTrue(c.isRunning)

        lifecycle.foreground()
        runCurrent()
        assertEquals(2, p.startCount)

        p.simulateUnexpectedStop(retryable = true) // 복귀로 횟수 초기화 → 다시 켤 여지가 있다
        advanceTimeBy(3_001); runCurrent()
        assertEquals(3, p.startCount)
        assertTrue(c.isRunning)
    }

    /** 세션을 닫으면 코어가 이유와 함께 알린다(FloorSession.onStopped) — end() 는 ENDED, 엔진 포기는 ENGINE_FAILED(iOS). */
    @Test fun closingNotifiesWithReason() = runTest {
        val c = started()
        val reasons = mutableListOf<co.onecheck.ones1ght.android.FloorSession.StopReason>()
        c.onSessionClosed = { reasons += it }
        c.stop()
        assertEquals(listOf(co.onecheck.ones1ght.android.FloorSession.StopReason.ENDED), reasons)
        c.stop() // 가동 중이 아니면 다시 알리지 않는다
        assertEquals(1, reasons.size)

        c.start(p)
        p.simulateUnexpectedStop(retryable = false)
        eventually { reasons.size == 2 }
        assertEquals(co.onecheck.ones1ght.android.FloorSession.StopReason.ENGINE_FAILED, reasons[1])
        assertFalse(c.isRunning)
    }
}
