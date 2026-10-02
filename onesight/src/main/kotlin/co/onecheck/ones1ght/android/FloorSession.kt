package co.onecheck.ones1ght.android

//
//  FloorSession.kt
//  측위 세션 — 앱이 측위를 켜고 끄고 이벤트를 받는 인스턴스.
//
//  · 층은 측위 엔진이 BLE 로 찾는다(setFloorMap 은 선택) — 만들 때 인자가 없다.
//  · **싱글턴** — UWB 라디오·측위 엔진·좌표 버퍼가 기기당 하나뿐이라
//    세션이 여럿이면 물리적으로 충돌한다. floorSession() 은 항상 같은 인스턴스를 준다.
//  · 가동 중 setFloorMap 을 다시 부르면 이 세션이 새 층으로 갈아탄다(재생성 불필요).
//  · 구역 이벤트·층의 표준 출구는 여기 리스너다(Ruling 1). 어느 provider 로 시작해도 provider 의 delegate
//    (onEmit·onFloorDetected) → 코어 → 여기로 같은 길을 탄다(iOS #55 K14). 앱이 UwbPositioningProvider 를 직접 만들어
//    begin(provider) 에 넣으면 그 provider 의 공개 훅(onZoneEvent 등)도 함께 불린다 — 앱 훅은 덮지 않는다(0.0.5~).
//  · 내장 provider 는 한 번만 만들고 계속 쓴다. 그 안의 코루틴 스코프가 프로세스 수명이라
//    begin 마다 새로 만들면 새는 만큼 쌓인다(iOS 가 hub 를 재사용하는 것과 같다).
//
//  포팅 원본: FloorSession.swift.
//

import androidx.annotation.MainThread
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

    /**
     * 엔진이 층을 잡았다(층 ID) / 잃었다(`null`).
     *
     * 갱신된 로케이터는 BLE 로 자기 층을 알리므로, 엔진은 `begin()` 뒤 1~2초 안에 층을 스스로 찾는다. 넘어오는 ID 는
     * `OneS1ght.floors(buildingId)` 가 주는 `Floor.id` 와 같은 값이다 — 그 층으로 `setFloorMap` 하면 된다. 어느 provider
     * 로 시작해도 온다(앱이 만든 [UwbPositioningProvider] 의 `onFloorDetected` — 엔진 층 번호 — 는 따로 그대로 불린다).
     * 층을 바꾸려고 측위를 끄지 말 것 — 가동 중 `setFloorMap` 은 안전하고 세션을 유지한다.
     */
    @Volatile public var onFloorDetected: SessionFloorListener? = null

    /**
     * 측위 세션이 닫혔다 — `end()` 를 불렀거나([StopReason.ENDED]), 엔진이 다시 켜지지 않아 SDK 가 닫았다
     * ([StopReason.ENGINE_FAILED]). 불릴 때 [isRunning] 은 이미 false 다 — ENGINE_FAILED 면 원인(권한·Bluetooth 등)을
     * 풀고 `begin()` 으로 다시 연다. 이 콜백이 없던 동안 SDK 가 세션을 닫아도 앱 화면은 「찾는 중」에 머물렀다(iOS S6).
     */
    @Volatile public var onStopped: SessionStoppedListener? = null

    /**
     * 세션이 닫힌 이유(iOS `FloorSession.StopReason`).
     *
     * ⚠️ 새 이유가 늘 수 있다 — `when` 에는 `else` 를 둘 것.
     */
    public enum class StopReason {
        /** 앱이 `end()` 를 불렀다(또는 `OneS1ght.reset()`·키 교체). */
        ENDED,

        /** 엔진이 스스로 꺼졌고 다시 켜지지 않아 SDK 가 세션을 닫았다. `E4001`·`E2003`·`E2004` 등이 함께 남는다. */
        ENGINE_FAILED,
    }

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
     * 순서: Android 17 미만 → [SdkError.OsVersionTooLow] · UWB 칩 없음 → [SdkError.DeviceNotSupported]
     * → 내장 provider 재사용 → (준비 안 됐으면 준비, 됐으면 키 재조회 재시도) → 가동.
     *
     * @throws SdkError.NotInitialized · SdkError.NotIdentified · SdkError.DeviceNotSupported · SdkError.OsVersionTooLow
     */
    @JvmSynthetic
    public suspend fun begin(): Unit = OneS1ght.onCore {
        requirePositioningDevice()
        val context = OneS1ght.appContext
        if (OneS1ght.coordinatorRef == null || context == null) throw SdkError.NotInitialized()

        val hub = builtInProvider ?: SdkWiring.builtInProviderFactory(context).also { builtInProvider = it }
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
     * 측위 시작 (provider 주입).
     *
     * - 앱이 만든 [UwbPositioningProvider] 면 [begin] 과 **똑같이** 다룬다 — 같은 기기 게이트, SDK 가 넣는
     *   라이선스, 엔진 로그 → `OneS1ght.onDebugLog`. provider 에 앱이 단 훅(onZoneEvent·onLog …)은 그대로 둔다
     *   (덮지 않는다). 지도 화면처럼 엔진 상태를 직접 지켜봐야 할 때 쓴다.
     * - 그 밖의 provider(커스텀·데모 등)는 기기 게이트를 거치지 않는다.
     * - 구역 콜백(onZoneEnter/Exit/Dwell)·onFloorDetected·일시정지는 어느 provider 든 같은 길(delegate)로 온다.
     *
     * @throws SdkError.NotInitialized · SdkError.NotIdentified — UwbPositioningProvider 면 여기에
     *   SdkError.DeviceNotSupported · SdkError.OsVersionTooLow 가 더해진다.
     */
    @JvmSynthetic
    public suspend fun begin(provider: PositioningProvider): Unit = OneS1ght.onCore {
        if (provider is UwbPositioningProvider) {
            // 초기화 전이면 칩을 물을 Context 가 없어 "미지원" 으로 잘못 나간다 — 초기화부터 본다.
            if (OneS1ght.coordinatorRef == null) throw SdkError.NotInitialized()
            requirePositioningDevice()
        }
        start(provider)
    }

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
     * 둔 채 좌표만 버리므로 `resume()` 이 즉시 이어진다. 쌓인 좌표는 그대로 둔다. 어느 provider 로 시작했든
     * 그 provider 의 pause() 를 부른다. 백그라운드에 다녀와도 일시정지는 유지된다 — 풀리는 것은 `resume()`·`end()`·
     * `begin()` 뿐이다(iOS 와 같다). 메인 스레드에서 부른다.
     */
    @MainThread
    public fun pause() {
        OneS1ght.coordinatorRef?.activeProvider?.pause()
    }

    /** 일시정지 해제. 메인 스레드에서 부른다. */
    @MainThread
    public fun resume() {
        OneS1ght.coordinatorRef?.activeProvider?.resume()
    }

    /** 측위 종료 + 잔여 좌표 전송. 초기화·층 설정은 유지 → begin 재호출로 재개. */
    @JvmSynthetic
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
     * ⚠️ **측위 엔진 라이선스는 여기서 SDK 가 넣는다.** 호스트 앱이 넣을 일이 아니다 — 고객은
     *    OneS1ght 하나만 붙이고, 그 아래에서 어떤 엔진이 도는지도 그 엔진이 무슨 키를 요구하는지도
     *    알 필요가 없다. 라이선스의 유일한 출처는 콘솔 `/config` 다(없으면 E1007).
     *    iOS 와 달리 키 재조회(순단 회복) **뒤에** 넣는다 — 방금 받아 온 키가 이번 begin 에 바로 쓰인다.
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
        if (provider is UwbPositioningProvider) {
            provider.license = coordinator.positioningLicense.orEmpty()
            // SDK 내부 연결 — 앱이 provider 에 단 훅(onLog)과는 따로 건다(덮지 않는다). 구역 이벤트·층은 delegate 로 온다.
            provider.sessionLogSink = { level, line -> OneS1ght.onDebugLog?.onLog(level, line) } // 엔진 로그 → 표준 디버그 훅
        }
        coordinator.start(provider)
    }

    /** 기기를 막는 곳 — initialize 는 기기를 보지 않는다. 내장 측위를 쓰는 begin 만 부른다. */
    private fun requirePositioningDevice() {
        val capability = SdkWiring.deviceCapability
        if (capability.sdkInt < MIN_POSITIONING_SDK) throw SdkError.OsVersionTooLow()
        if (!capability.hasUwbHardware()) throw SdkError.DeviceNotSupported()
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
        onFloorDetected = null
        onStopped = null
        builtInProvider = null
    }

    internal companion object {
        /** 세션 싱글턴 — OneS1ght.floorSession() 만 접근. */
        val shared: FloorSession = FloorSession()
    }
}
