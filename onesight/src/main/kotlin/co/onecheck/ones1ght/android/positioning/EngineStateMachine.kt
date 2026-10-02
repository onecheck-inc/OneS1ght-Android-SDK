package co.onecheck.ones1ght.android.positioning

//
//  EngineStateMachine.kt
//  측위 엔진 자체 상태(가동·탐색·추적·정지) — 측위 가동(isRunning)과는 별개다.
//  엔진은 층을 찾기 위해 measurement 보다 먼저 돌 수 있다.
//
//  일시정지는 엔진을 끄지 않는다 — 그게 stop() 과 갈리는 유일한 지점이고, 이게 무너지면
//  재개할 때마다 앵커를 처음부터 찾게 되어 걷기 검증이 못 쓰게 된다. 그래서 pause()/resume()
//  은 phase 를 건드리지 않고 isPaused 와 acceptsPosition() 으로만 소비를 막는다.
//
//  엔진 대응: openSession = 엔진 start · closeSession = 엔진 stop · onOpened = onStarted ·
//  onTrackingStarted/Stopped = 층 추적 시작/종료 · onClosed = onStopped · restart = 구역 재적재.
//
//  포팅 원본: UwbPositioningProvider.swift 의 phase·startAfterStop·pause·reloadingGeofences 로직.
//

import co.onecheck.ones1ght.android.runtime.LogLevel
import co.onecheck.ones1ght.android.runtime.SdkLocalized

/** 엔진 자체 상태. `IDLE` 이 아니면 엔진이 돌고 있다. */
internal enum class Phase { IDLE, STARTING, SEARCHING, TRACKING, STOPPING }

/**
 * 엔진 상태 기계 — 실제 세션 열기/닫기는 [openSession]/[closeSession] 콜백에 위임한다
 * (이 클래스는 android.* 를 모른다 — `positioning/Uwb*` 어댑터가 그 자리를 채운다).
 *
 * `stop()` 직후 곧바로 `start()` 가 오는 경합(백그라운드→포그라운드 전환 등)을 다룬다:
 * 세션이 내려가는 중(STOPPING)에 온 `start()` 는 즉시 열지 않고 예약([startAfterStop])만
 * 해 두었다가, [onClosed] 가 정리를 마친 뒤 이어받는다. 그 사이에 다시 `stop()` 이 오면
 * (최신 의사가 "끄겠다") 예약을 지운다 — 두 번째 `stop()` 은 이미 STOPPING/IDLE 이라
 * [closeSession] 을 다시 부르지 않는다.
 */
internal class EngineStateMachine(
    private val openSession: () -> Unit,
    private val closeSession: () -> Unit,
    private val log: (LogLevel, String) -> Unit,
) {
    internal var phase: Phase = Phase.IDLE
        private set

    internal var isRunning: Boolean = false
        private set

    internal var isPaused: Boolean = false
        private set

    /** 정지가 끝나는 대로 다시 띄워야 하는가 — 내려가는 중에 start 가 온 경우. */
    private var startAfterStop = false

    /**
     * 내부 재시작(구역 재적재·정지 중 거절된 시작의 재시도) 중인가 — 이때는 [onClosed] 가 isRunning·isPaused 를 그대로 둔 채
     * 곧바로 다시 연다. 호스트가 켠 측위·일시정지 상태가 재시작 한 번에 뒤집히면 안 된다.
     */
    private var restarting = false

    internal fun start() {
        if (isRunning) return
        if (phase == Phase.STOPPING) {
            startAfterStop = true
            log(LogLevel.LOG, SdkLocalized.t("uwb.startQueued"))
            return
        }
        // ⚠️ isRunning 을 **먼저** 세운다(감사 SP-B3). 엔진 콜백은 운영 디스패처(Main.immediate)에서 같은 호출
        //    안으로 곧바로 들어올 수 있다 — openSession 안에서 시작이 접혀 IDLE 로 정리된 뒤에 isRunning=true 를
        //    덮으면 IDLE 인데 측위 중으로 남아, 이후 start() 가 전부 no-op 이었다.
        isRunning = true
        isPaused = false
        if (phase == Phase.IDLE) {
            phase = Phase.STARTING
            openSession()
        }
    }

    /**
     * 엔진만 띄운다 — 측위(isRunning)는 켜지 않는다. 층을 먼저 찾을 때 쓴다(iOS startDetection).
     * IDLE 일 때만 연다 — 돌고 있거나 내려가는 중이면 아무것도 안 한다.
     * @return 새로 열었는가
     */
    internal fun openDetection(): Boolean {
        if (phase != Phase.IDLE) return false
        phase = Phase.STARTING
        openSession()
        return true
    }

    /** 예약된 start 만 지운다 — 엔진은 건드리지 않는다(측위가 꺼져 있을 때의 stop). */
    internal fun cancelPendingStart() {
        startAfterStop = false
    }

    internal fun stop() {
        // 예약된 start 가 있으면 먼저 지운다 — 끄겠다는 최신 의사가 이긴다.
        startAfterStop = false
        restarting = false
        if (phase == Phase.IDLE || phase == Phase.STOPPING) {
            isRunning = false
            isPaused = false
            return
        }
        isRunning = false
        isPaused = false
        phase = Phase.STOPPING
        closeSession()
    }

    internal fun onOpened() {
        if (phase == Phase.STARTING) phase = Phase.SEARCHING
    }

    /** 엔진이 층을 잡아 추적을 시작했다. 내려가는 중(STOPPING)에 늦게 온 통지는 무시한다. */
    internal fun onTrackingStarted() {
        if (phase == Phase.STARTING || phase == Phase.SEARCHING) phase = Phase.TRACKING
    }

    /** 엔진이 층을 잃었다 — 다시 탐색한다. */
    internal fun onTrackingStopped() {
        if (phase == Phase.TRACKING) phase = Phase.SEARCHING
    }

    /**
     * 세션만 닫았다 다시 연다 — isRunning·isPaused 는 유지한다(가동 중이 아니면 아무것도 안 함).
     * 닫힘이 끝나면([onClosed]) STARTING 으로 다시 연다.
     */
    internal fun restart() {
        if (!isRunning || phase == Phase.IDLE || phase == Phase.STOPPING) return
        restarting = true
        phase = Phase.STOPPING
        closeSession()
    }

    /**
     * 시작이 「엔진이 아직 내려가는 중(8)」으로 거절됐다 — 그 정지가 끝나는 대로 다시 연다. [restart] 와 달리
     * 측위 가동(isRunning)과 무관하다: 층 탐색 전용([openDetection])으로 띄운 경우에도 다시 열어야 한다
     * (감사 SP-B4: 예전엔 isRunning 가드에 막혀 STARTING 에 영원히 머물렀다).
     */
    internal fun retryOpenAfterStop() {
        if (phase != Phase.STARTING) return
        restarting = true
        phase = Phase.STOPPING
        closeSession()
    }

    internal fun onClosed() {
        if (restarting) {
            restarting = false
            phase = Phase.STARTING
            openSession()
            return
        }
        phase = Phase.IDLE
        isRunning = false
        // 내려가는 동안 들어와 있던 start 를 이제 이어받는다 — phase 가 IDLE 이 됐으니
        // 정상 경로를 그대로 탄다.
        if (startAfterStop) {
            startAfterStop = false
            start()
        }
    }

    /**
     * 좌표 소비만 멈춘다 — 엔진은 계속 돈다.
     * `stop()` 과 다르다: stop 은 엔진까지 꺼서 층·앵커를 잃는다.
     */
    internal fun pause() {
        if (!isRunning || isPaused) return
        isPaused = true
        log(LogLevel.INFO, SdkLocalized.t("uwb.paused"))
    }

    /**
     * 일시정지 해제. 반환값이 `true` 면 호출자가 판정기(zone judge)를 reset 해야 한다 —
     * 멈춰 있는 동안 들어온 영역 이벤트를 버렸으므로, 판정기가 옛 안/밖 상태를 그대로
     * 들고 있으면 이탈이 영영 안 나오고 다음 진입도 통째로 묻힌다.
     */
    internal fun resume(): Boolean {
        if (!isPaused) return false
        isPaused = false
        log(LogLevel.INFO, SdkLocalized.t("uwb.resumed"))
        return true
    }

    /** 좌표·존 이벤트를 지금 밖으로 내보내도 되는가. */
    internal fun acceptsPosition(): Boolean = isRunning && !isPaused
}
