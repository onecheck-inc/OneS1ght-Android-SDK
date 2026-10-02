package co.onecheck.ones1ght.android.positioning

//
//  MockPositioningProvider.kt
//  테스트/데모용 가짜 측위 — 콜백을 프로그램적으로 발생시켜 SDK 파이프라인을 검증한다.
//
//  실기기·UWB 없이: mock.simulateEnter(...) → SDK가 floors 로드하는지,
//  simulateZone(...) → events/zone 나가는지, simulatePosition(...) → 버퍼→벌크 나가는지.
//
//  포팅 원본: MockPositioningProvider.swift.
//

import co.onecheck.ones1ght.android.model.Coordinates
import co.onecheck.ones1ght.android.model.ZoneEventStatus

public class MockPositioningProvider : PositioningProvider {

    override var delegate: PositioningProviderDelegate? = null

    public var isRunning: Boolean = false
        private set

    /** apply()로 주입받은 config (테스트 검증용) */
    public var appliedBuildingId: String? = null
        private set

    public var appliedFloorId: String? = null
        private set

    public var appliedConfig: PositioningConfig? = null
        private set

    /** 코어가 "영역이 바뀌었다" 고 판단한 횟수 — 테스트가 이 값을 본다. */
    public var reloadGeofencesCount: Int = 0
        private set

    /** start() 가 불린 횟수 — 코어가 엔진을 다시 켰는지 테스트가 본다. */
    public var startCount: Int = 0
        private set

    /**
     * 다음 start() 를 그 자리에서 접는다 — 실제 엔진의 「라이선스 없음·권한 이미 거부」 처럼 동기로.
     * null = 정상, true/false = 그 retryable 값으로 [PositioningProviderDelegate.onStoppedUnexpectedly] 를 부른다.
     */
    public var failNextStartSynchronously: Boolean? = null

    override fun start() {
        startCount += 1
        failNextStartSynchronously?.let { retryable ->
            failNextStartSynchronously = null
            delegate?.onStoppedUnexpectedly(this, retryable, "mock sync")
            return
        }
        isRunning = true
    }

    override fun stop() {
        isRunning = false
    }

    override fun apply(buildingId: String, floorId: String) {
        appliedBuildingId = buildingId
        appliedFloorId = floorId
    }

    override fun apply(config: PositioningConfig) {
        appliedConfig = config
    }

    override fun reloadGeofences() {
        reloadGeofencesCount += 1
    }

    // MARK: - 시뮬레이션 트리거 (테스트·데모가 호출)

    /** 엔진이 스스로 꺼졌다(시작이 접혔거나 엔진 오류) — 실제 엔진처럼 꺼진 뒤 코어에 알린다. */
    @JvmOverloads
    public fun simulateUnexpectedStop(retryable: Boolean, context: String = "mock") {
        isRunning = false
        delegate?.onStoppedUnexpectedly(this, retryable, context)
    }

    /** 빌딩 입장 발생 */
    public fun simulateEnter(buildingId: String) {
        delegate?.onEnter(this, buildingId)
    }

    /** 좌표 fix 발생 */
    public fun simulatePosition(c: Coordinates, floorId: String?, atMs: Long) {
        delegate?.onPosition(this, c, floorId, atMs)
    }

    /** 존 판정 발생 */
    public fun simulateZone(zoneId: String, status: ZoneEventStatus, floorId: String?, atMs: Long) {
        delegate?.onZone(this, zoneId, status, floorId, atMs)
    }
}
