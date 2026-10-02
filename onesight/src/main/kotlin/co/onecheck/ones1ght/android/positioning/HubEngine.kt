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

    /**
     * 엔진 콜백 — 모양은 엔진 공개 리스너와 1:1.
     *
     * 계약(엔진 1.1.0 실측·README 기준 — provider 가 이 순서를 전제로 짜여 있다):
     *  · 콜백은 **엔진 스레드**에서 온다. provider 는 받자마자 코어 디스패처로 넘기고 세대(기동 번호)로 늦은 콜백을 거른다.
     *  · [start] 뒤에는 [onStarted] 또는 [onError](시작을 접는 번호 — HubError.abortsStart) 중 하나가 온다. 시작 단계
     *    실패에는 [onStopped] 가 **오지 않는다** — provider 가 스스로 되돌린다.
     *  · [onStopped] 는 [stop] 뒤, 또는 돌던 엔진이 스스로 멈출 때 한 번 온다(그때는 보통 [onError] 가 먼저 온다).
     *  · [onTrackingStarted] / [onTrackingStopped] 는 층을 잡고 놓칠 때마다, [onPosition]·[onAreaEvent] 는 층을 잡은
     *    동안에만 온다.
     *  · 엔진은 프로세스 싱글턴이라 리스너는 하나만 산다 — 여러 provider 는 HubListenerMux 로 나눠 받는다.
     *    리스너를 바꾼 뒤에도 앞 기동의 늦은 콜백(특히 onStopped)이 새 리스너에 닿을 수 있다.
     */
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
