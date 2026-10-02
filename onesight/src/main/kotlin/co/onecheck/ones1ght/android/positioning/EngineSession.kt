package co.onecheck.ones1ght.android.positioning

//
//  EngineSession.kt
//  측위 엔진 한 번의 기동 — 라이선스 등록 → 리스너(새 세대) → start, 정지와 정지 감시, 늦은 콜백 거르기.
//
//  [UwbPositioningProvider] 에서 떼어 냈다(감사 SP-C1). 엔진 콜백은 엔진 스레드에서 오고, 여기서 코어 디스패처로
//  넘긴 뒤 [sink] 에 전한다. 콜백마다 기동 세대([generation])를 달아, 이미 내린 기동의 늦은 콜백이 새 기동을
//  건드리지 않게 한다. 엔진 계약은 [HubEngine.Listener] 머리말.
//

import co.onecheck.ones1ght.android.runtime.LogLevel
import co.onecheck.ones1ght.android.runtime.SdkErrorCode
import co.onecheck.ones1ght.android.runtime.SdkLocalized
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal class EngineSession(
    private val engine: HubEngine,
    private val scope: CoroutineScope,
    /** 엔진 콜백을 받는 쪽(provider) — 코어 디스패처에서, 지금 세대의 것만 온다. */
    private val sink: HubEngine.Listener,
    /** 콜백 하나를 처리한 끝 — provider 가 관찰 상태를 옮긴다. */
    private val afterCallback: () -> Unit,
    private val log: (LogLevel, String) -> Unit,
) {
    /**
     * 엔진 라이선스 키. 콘솔 `/config` 의 측위 키를 FloorSession 이 넣어 준다.
     * 비어 있으면 시작하지 않고 E1007 로 알린다 (조용한 실패 금지).
     */
    var license: String = ""

    /** 마지막 엔진 오류 번호 — 스스로 멈춘 이유가 사람이 풀어야 하는 것인지 가를 때 본다. 기동마다 비운다. */
    var lastHubError: Int? = null

    /** 기동 세대 — 엔진을 띄울 때마다 1 증가. 엔진 스레드가 읽으므로 volatile. */
    @Volatile
    private var generation = 0

    /** STOPPING 감시 — onStopped 가 끝내 안 오면 멈춘 것으로 친다. */
    private var stopWatchdog: Job? = null

    /**
     * [open] 안에서 동기적으로 실패한 사유 — 상태 기계가 start 를 끝낸 뒤 provider 가 [takeOpenFailure] 로 가져가
     * 정리한다(상태 기계는 openSession 의 결과를 모른다).
     */
    private var openFailure: Pair<SdkErrorCode, String>? = null

    val version: String get() = runCatching { engine.version }.getOrDefault("?")
    val hardwareAvailable: Boolean get() = runCatching { engine.hardwareAvailable }.getOrDefault(false)

    /**
     * 엔진 기동 — 라이선스 등록 → 리스너(새 세대) → start. 결과는 콜백으로 온다.
     * @param onMissingLicense 라이선스가 비었을 때(엔진을 건드리기 전) — provider 가 앱 훅에 알린다.
     * @param beforeStart 새 세대를 연 직후 — provider 가 층 탐지 표시를 지운다.
     */
    fun open(onMissingLicense: () -> Unit, beforeStart: () -> Unit) {
        val key = license.trim()
        if (key.isEmpty()) {
            log(LogLevel.ERROR, SdkLocalized.t("uwb.noLicense"))
            onMissingLicense()
            openFailure = SdkErrorCode.KEY_UNAVAILABLE to "reason=engine_license_empty"
            return
        }
        generation += 1
        lastHubError = null
        beforeStart()
        try {
            engine.setLicense(key)
            engine.setListener(listenerFor(generation))
            log(LogLevel.INFO, SdkLocalized.t("uwb.starting", "****"))
            engine.start()
        } catch (e: SecurityException) {
            openFailure = SdkErrorCode.PERMISSION_DENIED to "start: ${e.message}"
        } catch (e: RuntimeException) {
            openFailure = SdkErrorCode.UWB_SESSION_FAILED to "start: ${e.javaClass.simpleName} ${e.message}"
        }
    }

    /** [open] 의 동기 실패를 꺼낸다(한 번만). */
    fun takeOpenFailure(): Pair<SdkErrorCode, String>? = openFailure.also { openFailure = null }

    /**
     * 엔진 정지 — 끝나면 onStopped 가 온다. [STOP_TIMEOUT_MS] 안에 안 오면 [onTimeout] 이 멈춘 것으로 친다.
     * @param stillStopping 감시가 끝난 순간에도 아직 내려가는 중인가.
     */
    fun close(stillStopping: () -> Boolean, onTimeout: () -> Unit) {
        armStopWatchdog(stillStopping, onTimeout)
        try {
            engine.stop() // 정리가 끝나면 onStopped 가 온다
        } catch (e: RuntimeException) {
            log(LogLevel.WARN, "stop: ${e.javaClass.simpleName} ${e.message}")
        }
    }

    /**
     * 정지 요청 뒤 [STOP_TIMEOUT_MS] 안에 onStopped 가 안 오면 멈춘 것으로 친다. 이게 없으면
     * STOPPING 에 영원히 머물고 이후 start 는 예약만 되어 조용히 측위가 안 켜진다.
     * 세대를 올려 옛 기동의 늦은 콜백은 버린다(엔진이 아직 내려가는 중이면 다음 start 가
     * 오류 8 을 받고, 그 정지가 끝나는 대로 다시 띄운다).
     */
    private fun armStopWatchdog(stillStopping: () -> Boolean, onTimeout: () -> Unit) {
        stopWatchdog?.cancel()
        stopWatchdog = scope.launch {
            delay(STOP_TIMEOUT_MS)
            stopWatchdog = null
            if (!stillStopping()) return@launch
            generation += 1
            log(LogLevel.WARN, SdkLocalized.t("uwb.stopped") + " (timeout ${STOP_TIMEOUT_MS}ms)")
            onTimeout()
        }
    }

    /** 정지가 끝났다 — 감시를 내린다. */
    fun cancelStopWatchdog() {
        stopWatchdog?.cancel()
        stopWatchdog = null
    }

    /** 엔진 리스너를 뗀다 — 정지 직후가 아니라 onStopped 뒤(완전히 IDLE)에 부른다. */
    fun releaseListener() {
        engine.setListener(null)
    }

    private fun listenerFor(gen: Int): HubEngine.Listener = object : HubEngine.Listener {
        override fun onStarted() = onCore(gen) { sink.onStarted() }
        override fun onStopped() = onCore(gen) { sink.onStopped() }
        override fun onTrackingStarted(floorId: Long) = onCore(gen) { sink.onTrackingStarted(floorId) }
        override fun onTrackingStopped(floorId: Long) = onCore(gen) { sink.onTrackingStopped(floorId) }
        override fun onPosition(floorId: Long, x: Double, y: Double, z: Double) =
            onCore(gen) { sink.onPosition(floorId, x, y, z) }
        override fun onAreaEvent(floorId: Long, areaName: String, inOut: String) =
            onCore(gen) { sink.onAreaEvent(floorId, areaName, inOut) }
        override fun onError(code: Int, message: String) = onCore(gen) { sink.onError(code, message) }
    }

    private fun onCore(gen: Int, block: () -> Unit) {
        scope.launch {
            if (gen == generation) {
                block()
                afterCallback()
            }
        }
    }

    internal companion object {
        /** 정지 요청 뒤 onStopped 를 기다리는 최대 시간. */
        const val STOP_TIMEOUT_MS: Long = 5_000L
    }
}
