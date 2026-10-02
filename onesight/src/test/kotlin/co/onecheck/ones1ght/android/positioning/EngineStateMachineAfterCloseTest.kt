package co.onecheck.ones1ght.android.positioning

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 감사 SP-C3 — provider 가 「재시작 중」을 따로 플래그(reloading)로 들지 않고 상태 기계의 [EngineStateMachine.isRestarting]
 * 과 [EngineStateMachine.onClosed] 의 반환값으로 가른다. 그 값이 예전 플래그와 같은 순간에 같은 값인지 고정한다.
 */
class EngineStateMachineAfterCloseTest {

    private var opens = 0
    private val sm = EngineStateMachine(openSession = { opens += 1 }, closeSession = {}, log = { _, _ -> })

    @Test fun plainStopEndsIdle() {
        sm.start()
        sm.onOpened()
        sm.stop()
        assertFalse(sm.isRestarting)
        assertEquals(EngineStateMachine.AfterClose.IDLE, sm.onClosed())
        assertEquals(Phase.IDLE, sm.phase)
    }

    /** 구역 재적재(restart) — 내려가는 동안 isRestarting, 닫히면 곧바로 다시 연다(가동 유지). */
    @Test fun restartReopensAndKeepsRunning() {
        sm.start()
        sm.onOpened()
        sm.restart()
        assertTrue(sm.isRestarting)
        assertEquals(EngineStateMachine.AfterClose.RESTARTED, sm.onClosed())
        assertFalse(sm.isRestarting)
        assertTrue(sm.isRunning)
        assertEquals(Phase.STARTING, sm.phase)
        assertEquals(2, opens)
    }

    /** 정지 중 거절된 시작의 재시도(retryOpenAfterStop) — 층 탐색 전용이어도 재시작으로 친다. */
    @Test fun retryOpenAfterStopIsARestart() {
        sm.openDetection()
        sm.retryOpenAfterStop()
        assertTrue(sm.isRestarting)
        assertEquals(EngineStateMachine.AfterClose.RESTARTED, sm.onClosed())
        assertFalse(sm.isRunning)
    }

    /** 내려가는 중에 온 start — 닫히면 새 가동으로 이어받는다(재시작이 아니다). */
    @Test fun queuedStartIsAFreshStart() {
        sm.start()
        sm.onOpened()
        sm.stop()
        sm.start() // STOPPING 중 — 예약
        assertFalse(sm.isRestarting)
        assertEquals(EngineStateMachine.AfterClose.STARTED_QUEUED, sm.onClosed())
        assertTrue(sm.isRunning)
    }

    /** 재시작 중에 stop — 끄겠다는 최신 의사가 이긴다(재시작 표시도 지운다). */
    @Test fun stopDuringRestartClearsIt() {
        sm.start()
        sm.onOpened()
        sm.restart()
        sm.stop()
        assertFalse(sm.isRestarting)
        assertEquals(EngineStateMachine.AfterClose.IDLE, sm.onClosed())
    }
}
