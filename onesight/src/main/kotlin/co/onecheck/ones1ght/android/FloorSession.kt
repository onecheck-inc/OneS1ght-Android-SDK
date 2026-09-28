package co.onecheck.ones1ght.android

//
//  FloorSession.kt
//  측위 세션 — 앱이 측위를 켜고 끄고 이벤트를 받는 인스턴스.
//
//  · 층은 setFloorMap 이 이미 잡아 두었으므로 만들 때 인자가 없다.
//  · **싱글턴** — UWB 라디오·판정 엔진·좌표 버퍼가 기기당 하나뿐이라
//    세션이 여럿이면 물리적으로 충돌한다. floorSession() 은 항상 같은 인스턴스를 준다.
//  · 가동 중 setFloorMap 을 다시 부르면 이 세션이 새 층으로 갈아탄다(재생성 불필요).
//  · 이벤트는 여기 리스너로만 나간다(Ruling 1) — 내장 provider·판정 엔진의 훅은 내부 전용이다.
//  · 내장 provider 는 한 번만 만들고 계속 쓴다. 그 안의 코루틴 스코프가 프로세스 수명이라
//    begin 마다 새로 만들면 새는 만큼 쌓인다(iOS 가 hub 를 재사용하는 것과 같다).
//
//  포팅 원본: FloorSession.swift.
//

import co.onecheck.ones1ght.android.model.Floor
import co.onecheck.ones1ght.android.model.ZoneEvent
import co.onecheck.ones1ght.android.positioning.PositioningProvider
import co.onecheck.ones1ght.android.positioning.UwbPositioningProvider

public class FloorSession internal constructor() {

    // MARK: - 이벤트 수신 (전부 메인 스레드)

    /** 구역 진입 — 온디바이스 판정 즉시 (서버 왕복 없음). */
    @Volatile public var onZoneEnter: ZoneListener? = null

    /** 구역 이탈. */
    @Volatile public var onZoneExit: ZoneListener? = null

    /** 구역 체류 — dwellSeconds 도달 시 1회 (반복 발화 없음). 초 단위. */
    @Volatile public var onZoneDwell: DwellListener? = null

    /** 실시간 좌표 (도면 로컬 미터) — 지도에 내 위치를 그리는 표준 훅. */
    @Volatile public var onPosition: PositionListener? = null

    /** 존 이벤트 서버 응답의 개인화 액션 — (zoneId, triggers). */
    @Volatile public var onTriggers: TriggersListener? = null

    /**
     * 콘솔에서 무언가 바뀌었다 — 지도를 다시 그리거나 구역을 다시 받을 때 쓴다.
     *
     * **SDK 는 이 신호로 아무것도 하지 않는다.** 무엇을 다시 받을지는 앱이 정한다.
     * - `ZonesChanged` / `ResyncNeeded` → `OneS1ght.refreshZones()`. ⚠️ 연속해서 오면 접어라(권장 1초) —
     *   구역을 다시 물릴 때마다 진출입 판정이 처음부터 시작된다.
     * - `RulesChanged` → 지금 들어가 있는 구역이 있으면 그 구역의 이벤트를 한 번 다시 조회하라.
     */
    @Volatile public var onConfigChanged: ConfigChangeListener? = null

    // MARK: - 상태

    /** 이 세션이 보고 있는 층 — setFloorMap 이 정한 값. null 이면 층 미지정. */
    public val floor: Floor?
        get() = OneS1ght.coordinatorRef?.currentFloor

    /** 측위 가동 중인가. */
    public val isRunning: Boolean
        get() = OneS1ght.coordinatorRef?.isRunning ?: false

    /** 일시정지 중인가. */
    public val isPaused: Boolean
        get() = OneS1ght.coordinatorRef?.activeProvider?.isPaused ?: false

    // MARK: - 제어

    /**
     * 측위 시작 (매장 진입 시) — 내장 UWB 측위를 쓴다.
     *
     * 순서: Android 17 미만 → [SdkError.OsVersionTooLow] · 기기 미지원 → [SdkError.DeviceNotSupported]
     * → 내장 provider 재사용 → (준비 안 됐으면 준비, 됐으면 키 재조회 재시도) → 가동.
     *
     * @throws SdkError.NotInitialized · SdkError.NotIdentified · SdkError.DeviceNotSupported · SdkError.OsVersionTooLow
     */
    public suspend fun begin(): Unit = OneS1ght.onCore {
        // 기기를 막는 곳은 여기 하나뿐이다 — initialize 는 기기를 보지 않는다.
        val capability = OneS1ght.deviceCapability
        if (capability.sdkInt < MIN_POSITIONING_SDK) throw SdkError.OsVersionTooLow()
        if (!capability.supportsDlTdoa()) throw SdkError.DeviceNotSupported()
        val context = OneS1ght.appContext
        if (OneS1ght.coordinatorRef == null || context == null) throw SdkError.NotInitialized()

        val hub = builtInProvider ?: OneS1ght.builtInProviderFactory(context).also { builtInProvider = it }
        if (hub is UwbPositioningProvider) {
            hub.onZoneEvent = { event -> dispatch(event) }
            hub.onLog = { level, line -> OneS1ght.onDebugLog?.onLog(level, line) } // 엔진 로그 → 표준 디버그 훅
        }
        start(hub)
    }

    /** [begin] 의 Java 판. */
    public fun begin(callback: Callback<Void?>) {
        JavaBridge.run(callback) {
            begin()
            null
        }
    }

    /**
     * 측위 시작 (커스텀 측위 주입) — 테스트(Mock)·데모 등 특수 경우용. 기기 게이트를 거치지 않는다.
     *
     * @throws SdkError.NotInitialized · SdkError.NotIdentified
     */
    public suspend fun begin(provider: PositioningProvider): Unit = OneS1ght.onCore { start(provider) }

    /** [begin] (provider 주입) 의 Java 판. */
    public fun begin(provider: PositioningProvider, callback: Callback<Void?>) {
        JavaBridge.run(callback) {
            begin(provider)
            null
        }
    }

    /**
     * 측위 **일시정지** — 좌표 표시·수집·판정만 멈추고 엔진은 계속 돌린다.
     *
     * `end()` 와 다르다: end 는 엔진까지 꺼서 층·앵커를 잃는다. 이 호출은 층 추적을 그대로
     * 둔 채 좌표만 버리므로 `resume()` 이 즉시 이어진다. 쌓인 좌표는 그대로 둔다.
     * 메인 스레드에서 부른다.
     */
    public fun pause() {
        OneS1ght.coordinatorRef?.activeProvider?.pause()
    }

    /** 일시정지 해제. 메인 스레드에서 부른다. */
    public fun resume() {
        OneS1ght.coordinatorRef?.activeProvider?.resume()
    }

    /** 측위 종료 + 잔여 좌표 전송. 초기화·층 설정은 유지 → begin 재호출로 재개. */
    public suspend fun end(): Unit = OneS1ght.onCore { OneS1ght.coordinatorRef?.stop() }

    /** [end] 의 Java 판. */
    public fun end(callback: Callback<Void?>) {
        JavaBridge.run(callback) {
            end()
            null
        }
    }

    // MARK: - 내부

    /** 내장 provider 재사용 (재시작 대비) — 한 번 만들면 프로세스 끝까지 같은 인스턴스. */
    private var builtInProvider: PositioningProvider? = null

    /**
     * 가동 공통 경로 — 주입 provider 도 같은 대우를 받는다.
     *
     * TODO(Task 12): iOS 는 여기서 내장 provider 에 측위 엔진 라이선스(coordinator.positioningLicense)
     *  를 넣는다. 안드로이드 내장 provider 에는 아직 그 자리가 없다 — 생기면 이 한 곳에서 넣는다.
     */
    private suspend fun start(provider: PositioningProvider) {
        val coordinator = OneS1ght.coordinatorRef ?: throw SdkError.NotInitialized()
        if (!coordinator.isPrepared) {
            coordinator.prepare() // 순단 회복
        } else {
            // prepare() 는 끝났지만 콘솔 키 해석만 실패했을 수 있다 — 멱등 가드라 prepare()
            // 재호출로는 다시 못 붙는다. 여기서 따로 재시도한다.
            coordinator.retryKeyResolutionIfNeeded()
        }
        coordinator.start(provider)
    }

    /** ZoneEvent → 분리된 리스너. */
    internal fun dispatch(event: ZoneEvent) {
        when (event) {
            is ZoneEvent.Enter -> onZoneEnter?.onZone(event.zone)
            is ZoneEvent.Exit -> onZoneExit?.onZone(event.zone)
            is ZoneEvent.Dwell -> onZoneDwell?.onDwell(event.zone, event.seconds)
        }
    }

    /** 테스트 전용 — 리스너와 내장 provider 를 비운다. */
    internal fun clearForTest() {
        onZoneEnter = null
        onZoneExit = null
        onZoneDwell = null
        onPosition = null
        onTriggers = null
        onConfigChanged = null
        builtInProvider = null
    }

    internal companion object {
        /** 세션 싱글턴 — OneS1ght.floorSession() 만 접근. */
        val shared: FloorSession = FloorSession()
    }
}
