package co.onecheck.ones1ght.android.positioning

import co.onecheck.ones1ght.android.runtime.LogLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 엔진 상태 기계 — phase 전이·startAfterStop 경합·pause/resume.
 *
 * 포팅 원본: PositioningPauseTests.swift(UwbPositioningProvider.phase·pause·resume),
 * UwbPositioningProvider.swift 의 start()/stop()/hubStopped() (약 452~600행, startAfterStop 경합).
 */
class EngineStateMachineTest {

    private class Recorder {
        val opens = mutableListOf<Int>()
        val closes = mutableListOf<Int>()
        val logs = mutableListOf<Pair<LogLevel, String>>()
        private var seq = 0

        fun machine(): EngineStateMachine = EngineStateMachine(
            openSession = { opens.add(++seq) },
            closeSession = { closes.add(++seq) },
            log = { level, msg -> logs.add(level to msg) },
        )
    }

    // MARK: - 기본 전이

    @Test fun startsIdleAndNotRunningAndNotPaused() {
        val sm = Recorder().machine()
        assertEquals(Phase.IDLE, sm.phase)
        assertFalse(sm.isRunning)
        assertFalse(sm.isPaused)
    }

    @Test fun startOpensSessionAndMovesToStarting() {
        val r = Recorder()
        val sm = r.machine()

        sm.start()

        assertEquals(Phase.STARTING, sm.phase)
        assertTrue(sm.isRunning)
        assertFalse(sm.isPaused)
        assertEquals(1, r.opens.size)
    }

    @Test fun onOpenedMovesStartingToSearching() {
        val sm = Recorder().machine()
        sm.start()

        sm.onOpened()

        assertEquals(Phase.SEARCHING, sm.phase)
    }

    @Test fun onFirstFixMovesToTracking() {
        val sm = Recorder().machine()
        sm.start()
        sm.onOpened()

        sm.onFirstFix()

        assertEquals(Phase.TRACKING, sm.phase)
    }

    @Test fun stopClosesSessionAndMovesToStopping() {
        val r = Recorder()
        val sm = r.machine()
        sm.start()
        sm.onOpened()

        sm.stop()

        assertEquals(Phase.STOPPING, sm.phase)
        assertFalse(sm.isRunning)
        assertEquals(1, r.closes.size)
    }

    @Test fun onClosedMovesStoppingToIdle() {
        val sm = Recorder().machine()
        sm.start()
        sm.onOpened()
        sm.stop()

        sm.onClosed()

        assertEquals(Phase.IDLE, sm.phase)
        assertFalse(sm.isRunning)
    }

    @Test fun startCalledTwiceWhileRunningDoesNotReopenSession() {
        // 이미 돌고 있는데(isRunning) 또 start() 가 오면(중복 호출) openSession 을 다시
        // 부르지 않는다 — 멱등.
        val r = Recorder()
        val sm = r.machine()

        sm.start()
        sm.start()

        assertEquals(1, r.opens.size)
    }

    // MARK: - 경합: stop() 직후 start()

    @Test fun startAfterStopQueuesAndOpensOnlyOnceAfterClosed() {
        val r = Recorder()
        val sm = r.machine()
        sm.start()
        sm.onOpened()
        assertEquals(1, r.opens.size)

        sm.stop() // phase=STOPPING, closeSession 1회
        assertEquals(1, r.closes.size)

        sm.start() // 내려가는 중 — 예약만, openSession 은 아직 안 불린다
        assertEquals(1, r.opens.size)
        assertEquals(Phase.STOPPING, sm.phase)
        assertFalse(sm.isRunning)

        sm.onClosed() // 예약이 이어받아 openSession 을 이제 1회 더(총 2회) 부른다
        assertEquals(2, r.opens.size)
        assertEquals(Phase.STARTING, sm.phase)
        assertTrue(sm.isRunning)
    }

    @Test fun stopStartStopThenClosedDoesNotReopen() {
        val r = Recorder()
        val sm = r.machine()
        sm.start()
        sm.onOpened()

        sm.stop() // STOPPING, closeSession 1회
        sm.start() // 예약(startAfterStop=true)
        sm.stop() // 최신 의사는 "끄겠다" — 예약을 지운다. closeSession 은 다시 부르지 않는다.

        assertEquals(1, r.closes.size)

        sm.onClosed() // 예약이 취소됐으므로 다시 열지 않는다

        assertEquals(1, r.opens.size) // 최초 1회에서 더 늘지 않음
        assertEquals(Phase.IDLE, sm.phase)
        assertFalse(sm.isRunning)
    }

    @Test fun stopWhenNeverStartedIsNoop() {
        val r = Recorder()
        val sm = r.machine()

        sm.stop()

        assertEquals(0, r.closes.size)
        assertEquals(Phase.IDLE, sm.phase)
        assertFalse(sm.isRunning)
    }

    // MARK: - pause/resume (PositioningPauseTests 포팅)

    @Test fun testStartsUnpaused() {
        val sm = Recorder().machine()
        assertFalse(sm.isPaused)
    }

    @Test fun testPauseIsIgnoredWhenNotRunning() {
        val sm = Recorder().machine()

        sm.pause()

        assertFalse("안 돌고 있는데 일시정지가 걸렸다", sm.isPaused)
    }

    @Test fun testResumeIsIdempotent() {
        val sm = Recorder().machine()

        val first = sm.resume()
        val second = sm.resume()

        assertFalse(sm.isPaused)
        assertFalse(first)
        assertFalse(second)
    }

    @Test fun testStopClearsPause() {
        val sm = Recorder().machine()
        sm.start()
        sm.pause()
        assertTrue(sm.isPaused)

        sm.stop()

        assertFalse(sm.isPaused)
    }

    @Test fun pauseWhileRunningBlocksAcceptsPosition() {
        // ⚠️ 원본 회귀 지점: pause 는 isRunning 을 건드리지 않는다(엔진은 계속 돈다) —
        // 소비만 acceptsPosition() 에서 막는다.
        val sm = Recorder().machine()
        sm.start()
        sm.onOpened()
        assertTrue(sm.acceptsPosition())

        sm.pause()

        assertTrue(sm.isRunning) // 엔진은 계속 돈다
        assertFalse(sm.acceptsPosition())
    }

    @Test fun resumeReenablesAcceptsPositionAndReturnsTrue() {
        val sm = Recorder().machine()
        sm.start()
        sm.onOpened()
        sm.pause()
        assertFalse(sm.acceptsPosition())

        val resumed = sm.resume()

        assertTrue(resumed)
        assertTrue(sm.acceptsPosition())
    }

    @Test fun pauseWhenAlreadyPausedIsNoop() {
        val sm = Recorder().machine()
        sm.start()
        sm.pause()
        sm.pause()

        assertTrue(sm.isPaused)
    }

    @Test fun acceptsPositionFalseWhenIdle() {
        val sm = Recorder().machine()
        assertFalse(sm.acceptsPosition())
    }
}
