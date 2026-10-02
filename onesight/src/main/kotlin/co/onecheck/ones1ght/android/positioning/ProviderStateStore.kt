package co.onecheck.ones1ght.android.positioning

//
//  ProviderStateStore.kt
//  [UwbPositioningProvider] 의 관찰 상태 — 공개 `…Flow` 의 원본과 화면 로그 줄.
//
//  provider 에서 떼어 냈다(감사 SP-C1). 상태 기계·내부 값이 바뀐 뒤 provider 가 [publish] 로 한 번에 옮긴다 —
//  공개 동작·엔진 콜백 하나를 처리한 끝에서만 옮겨 중간 상태가 밖에 보이지 않게 한다.
//  스레드: 메인(코어 디스패처)에서만 쓴다.
//

import co.onecheck.ones1ght.android.model.Coordinates
import kotlinx.coroutines.flow.MutableStateFlow

internal class ProviderStateStore(
    /** 무엇이든 바뀌었다 — provider 가 앱의 ProviderChangeListener 로 잇는다. */
    private val onChanged: () -> Unit,
) {
    val phase = MutableStateFlow(UwbPositioningProvider.PositioningPhase.IDLE)
    val isRunning = MutableStateFlow(false)
    val isPaused = MutableStateFlow(false)
    val latestPosition = MutableStateFlow<Coordinates?>(null)

    /** 엔진이 추적 중인 층 — publish 를 기다리지 않고 곧바로 바뀐다(층 콜백과 같은 순간에 보여야 한다). */
    val detectedFloorId = MutableStateFlow<Long?>(null)
    val measurementCount = MutableStateFlow(0)
    val log = MutableStateFlow<List<String>>(emptyList())

    /** 화면 로그(최근 [UwbPositioningProvider.LOG_CAPACITY] 줄). */
    private val logLines = ArrayDeque<String>()

    /** 로그 한 줄을 더한다 — 넘치면 오래된 것부터 버린다. 변경 통지는 provider 가 따로 한다. */
    fun appendLog(line: String) {
        logLines.addLast(line)
        while (logLines.size > UwbPositioningProvider.LOG_CAPACITY) logLines.removeFirst()
        log.value = logLines.toList()
    }

    /** 상태 기계·내부 값 → 관찰 상태. 바뀐 것이 있으면 [onChanged] 를 한 번 부른다. */
    fun publish(
        phase: UwbPositioningProvider.PositioningPhase,
        isRunning: Boolean,
        isPaused: Boolean,
        latestPosition: Coordinates?,
        measurementCount: Int,
    ) {
        var changed = false
        fun <T> MutableStateFlow<T>.set(value: T) {
            if (this.value != value) {
                this.value = value
                changed = true
            }
        }
        this.phase.set(phase)
        this.isRunning.set(isRunning)
        this.isPaused.set(isPaused)
        this.latestPosition.set(latestPosition)
        this.measurementCount.set(measurementCount)
        if (changed) onChanged()
    }
}
