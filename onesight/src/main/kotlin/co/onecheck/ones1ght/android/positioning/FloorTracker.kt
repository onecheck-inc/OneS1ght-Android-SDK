package co.onecheck.ones1ght.android.positioning

//
//  FloorTracker.kt
//  층 — 콘솔이 지정한 층과 엔진이 잡은 층, 둘의 대조(E3008), 층 미탐지 감시(E3007), 서버에 실을 층 ID.
//
//  [UwbPositioningProvider] 에서 떼어 냈다(감사 SP-C1). 엔진의 floorId(Long)는 공간 서비스 층 번호이고 콘솔 층도
//  같은 값을 문자열("14")로 준다 — 그래서 서버로 나가는 floor_id 는 엔진 층 그대로이고(없으면 콘솔 층), 둘이
//  어긋나면 E3008. 스레드: 메인(코어 디스패처)에서만 쓴다.
//

import co.onecheck.ones1ght.android.runtime.SdkErrorCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal class FloorTracker(
    private val scope: CoroutineScope,
    private val state: ProviderStateStore,
    private val report: (SdkErrorCode, String) -> Unit,
) {
    /** apply(buildingId, floorId) 로 받은 콘솔 건물 ID. */
    var buildingId: String = ""
        private set

    /** apply(buildingId, floorId) 로 받은 콘솔 층 ID — 대조(E3008)와 엔진 층이 없을 때의 귀속용. 빈 값 = 층 해제. */
    var consoleFloorId: String = ""
        private set

    /** 엔진이 지금 추적 중인 층(공간 서비스 층 번호). `null` = 층 탐색 중. */
    val detected: Long? get() = state.detectedFloorId.value

    /** 층 불일치는 층마다 한 번만 알린다 — 층이 유지되는 동안 반복하면 로그가 덮인다. */
    private var warnedMismatch: Long? = null

    /** 층 미탐지 감시(E3007). */
    private var watch: Job? = null

    /** 콘솔 건물·층 — 대조 기준이 바뀌었으니 새 층 기준으로 다시 한 번 알린다. */
    fun applyConsoleFloor(buildingId: String, floorId: String) {
        this.buildingId = buildingId
        this.consoleFloorId = floorId
        warnedMismatch = null
    }

    /** 새 가동 — 불일치 경고를 다시 한 번 할 수 있게 한다. */
    fun resetMismatchWarning() {
        warnedMismatch = null
    }

    fun setDetected(fid: Long?) {
        state.detectedFloorId.value = fid
    }

    /**
     * 엔진이 잡은 층 ↔ 콘솔이 지정한 층 대조.
     *
     * 서버로 나가는 `floor_id` 는 엔진 값이다. 두 값이 어긋난 채로 두면 좌표·존 이벤트가 콘솔이
     * 모르는 층에 쌓여, 화면에서는 "데이터가 없다"로만 보인다. 콘솔 층이 아직 안 정해졌으면
     * (setFloorMap 전) 대조하지 않는다 — 그건 불일치가 아니다.
     */
    fun checkAgreement(fid: Long) {
        if (consoleFloorId.isEmpty() || consoleFloorId == fid.toString()) return
        if (warnedMismatch == fid) return
        warnedMismatch = fid
        report(SdkErrorCode.FLOOR_ID_MISMATCH, "engine=$fid console=$consoleFloorId")
    }

    /** 서버에 실을 층 ID — 엔진이 잡은 층이 있으면 그 번호, 없으면 콘솔에서 주입받은 값. */
    fun currentFloorId(): String? = detected?.toString() ?: consoleFloorId.ifEmpty { null }

    /**
     * 층 미탐지 감시 — 측위를 켰는데 [UwbPositioningProvider.FLOOR_DETECT_DELAY_MS] 가 지나도록 층이 안 잡히면 E3007.
     * 엔진은 층을 못 찾아도 오류를 주지 않고 계속 탐색만 한다 — 앱에서는 "그냥 좌표가 안 나온다"
     * 로만 보여서, 이 감시가 없으면 BLE 미수신이 아무 흔적도 남기지 않는다.
     */
    fun startWatch(isRunning: () -> Boolean, phaseName: () -> String) {
        cancelWatch()
        if (detected != null) return
        watch = scope.launch {
            delay(UwbPositioningProvider.FLOOR_DETECT_DELAY_MS)
            watch = null
            if (!isRunning() || detected != null) return@launch
            report(
                SdkErrorCode.FLOOR_NOT_DETECTED,
                "phase=${phaseName()} after=${UwbPositioningProvider.FLOOR_DETECT_DELAY_MS / 1000}s",
            )
        }
    }

    fun cancelWatch() {
        watch?.cancel()
        watch = null
    }
}
