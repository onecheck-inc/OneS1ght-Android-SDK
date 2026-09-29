package co.onecheck.ones1ght.android.positioning

//
//  HubEngine.kt
//  측위 엔진 계약 — [UwbPositioningProvider] 가 보는 엔진의 모양.
//
//  실제 구현은 [IntelligenceHubEngine](내장 통합 측위 엔진). 이 인터페이스는 android.* 도 엔진
//  타입도 모른다 — provider 의 오케스트레이션(상태 기계·일시정지·층 감시·오류 매핑·영역 매핑)을
//  JVM 단위테스트가 가짜 엔진으로 그대로 밟게 하려고 떼어 냈다.
//
//  엔진이 하는 일(전부 엔진 몫 — SDK 는 감싸기만 한다):
//    BLE 로 층을 고르고 → 자기 서버에서 앵커·지오펜스를 받아 → UWB(DL-TDoA)로 좌표를 내고 →
//    영역 진출입(IN/OUT)까지 판정한다.
//

internal interface HubEngine {

    /** 엔진 라이브러리 버전(로그용). */
    val version: String

    /** 이 기기에 UWB 칩이 있는가 — 빠른 사전 힌트. 정확한 DL-TDoA 지원 판정은 start 가 한다(12). */
    val hardwareAvailable: Boolean

    /** 라이선스 등록 — 보관만 한다. 검증은 [start] 가 서버로 한다(거부 10 · 미도달 11). */
    fun setLicense(key: String)

    /** 콜백 등록·교체·해제(null). 콜백은 엔진 스레드에서 온다. */
    fun setListener(listener: Listener?)

    /** 비동기 시작 — 반드시 onStarted 또는 onError 중 하나가 온다. */
    fun start()

    /** 비동기 정지 — 끝나면 onStopped. 이미 정지(중)면 아무 일도 없다. */
    fun stop()

    /** 엔진 콜백 — 모양은 엔진 공개 리스너와 1:1. */
    interface Listener {
        fun onStarted()
        fun onStopped()
        fun onTrackingStarted(floorId: Long)
        fun onTrackingStopped(floorId: Long)
        fun onPosition(floorId: Long, x: Double, y: Double, z: Double)
        fun onAreaEvent(floorId: Long, areaName: String, inOut: String)
        fun onError(code: Int, message: String)
    }
}
