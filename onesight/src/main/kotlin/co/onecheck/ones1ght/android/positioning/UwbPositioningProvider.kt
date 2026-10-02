package co.onecheck.ones1ght.android.positioning

//
//  UwbPositioningProvider.kt
//  실측위 어댑터 (내장) — 통합 측위 엔진을 감싼다.
//
//  역할 분담 (iOS 0.1.24 와 같다):
//    · 엔진     — BLE 로 층을 고르고, 자기 서버에서 앵커·지오펜스를 받아 UWB(DL-TDoA)로 좌표를 내고,
//                 영역 진출입(IN/OUT)까지 준다.
//    · OneS1ght — 좌표를 서버에 수집하고(delegate.onPosition), 영역 이벤트를 콘솔 zone_id 로 옮겨
//                 /events/zone → 시책(쿠폰)까지 기존 경로 그대로 태운다.
//
//  진단 한계 (자체 레인징 시절 대비 후퇴 — 인지하고 쓸 것):
//    엔진은 앵커 목록도, 앵커별 수신 상태도 노출하지 않는다. 그래서 "등록 4대 중 3대 수신 —
//    미수신 0x0042" 같은 특정이 불가능하다. 콘솔 로케이터는 '등록' 기준으로만 쓰고, 좌표가 나오면
//    전부 수신으로 친다 — 모르는 것을 고장으로 칠하지 않는다(canAttributePerAnchor=false).
//
//  층 ID: 엔진의 floorId(Long)는 공간 서비스 층 번호이고, 콘솔 층도 같은 값을 문자열("14")로 준다.
//  그래서 서버로 나가는 floor_id 는 엔진 층 그대로이고(없으면 콘솔 층), 둘이 어긋나면 E3008.
//
//  스레드: 엔진 콜백은 엔진 스레드에서 오고, 전부 주입된 코어 디스패처로 넘긴 뒤 처리한다.
//  콜백마다 기동 세대([generation])를 달아, 이미 내린 기동의 늦은 콜백이 새 기동을 건드리지 않게 한다.
//
//  공개 표면(0.0.5~): iOS 0.1.24 의 UwbPositioningProvider 와 같은 것을 연다 — 앱이 직접 만들어
//  FloorSession.begin(provider) 에 넣고, 상태(phase·latestPosition …)를 지켜보며 지도를 그린다.
//  iOS @Published 는 Kotlin StateFlow(`…Flow`) + 게터 + [ProviderChangeListener](Java) 로 옮겼다.
//
//  포팅 원본: UwbPositioningProvider.swift (iOS 0.1.24 — 통합 엔진 경로).
//

import android.content.Context
import android.os.Build
import androidx.annotation.MainThread
import androidx.annotation.RequiresApi
import co.onecheck.ones1ght.android.DebugLogListener
import co.onecheck.ones1ght.android.MIN_POSITIONING_SDK
import co.onecheck.ones1ght.android.OneS1ght
import co.onecheck.ones1ght.android.model.Coordinates
import co.onecheck.ones1ght.android.model.Zone
import co.onecheck.ones1ght.android.model.ZoneEvent
import co.onecheck.ones1ght.android.model.ZoneEventStatus
import co.onecheck.ones1ght.android.runtime.LogLevel
import co.onecheck.ones1ght.android.runtime.SdkErrorCode
import co.onecheck.ones1ght.android.runtime.SdkLocalized
import co.onecheck.ones1ght.android.zone.CoroutineDwellScheduler
import co.onecheck.ones1ght.android.zone.DwellScheduler
import co.onecheck.ones1ght.android.zone.UwbAreaJudge
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 내장 UWB 측위 제공자 — 측위 엔진(층 탐지·UWB 측위·영역 판정)을 감싼다.
 *
 * 보통은 앱이 만들 일이 없다 — `OneS1ght.floorSession().begin()` 이 안에서 만든다. 지도 화면처럼
 * 엔진 상태([phase]·[latestPosition]·[detectedFloorId] …)를 직접 지켜봐야 할 때만 앱이 만들어
 * `begin(provider)` 에 넣는다:
 *
 * ```kotlin
 * val provider = UwbPositioningProvider(context)
 * provider.onFloorDetected = FloorDetectedListener { floorId -> … }
 * OneS1ght.floorSession().begin(provider)          // begin() 과 같은 대우(라이선스·구역 이벤트·로그)
 * provider.latestPositionFlow.collect { … }
 * ```
 *
 * 모든 멤버는 메인 스레드에서 부른다. 훅도 메인 스레드에서 불린다.
 */
public class UwbPositioningProvider private constructor(
    private val engine: HubEngine,
    main: CoroutineDispatcher,
    dwellScheduler: DwellScheduler?,
    private val clock: () -> Long,
) : PositioningProvider {

    /**
     * 실제 측위 엔진을 쓰는 provider 를 만든다. 어느 OS 에서든 던지지 않는다 — Android 17(API 37) 미만이면
     * 엔진 없이 만들어지고, 시작하면 미지원 기기 오류(E2002)로 끝난다. 쓰기 전에 [isSupported] 로 거른다.
     */
    public constructor(context: Context) : this(
        engine = realEngine(context),
        main = OneS1ght.dispatcher,
        dwellScheduler = null,
        clock = System::currentTimeMillis,
    )

    /** 엔진 자체 상태 — 측위 가동([isRunning])과는 별개다. 엔진은 층을 찾기 위해 측위보다 먼저 돌 수 있다. */
    public enum class PositioningPhase { IDLE, STARTING, SEARCHING, TRACKING, STOPPING }

    /**
     * 등록 로케이터 vs 수신 — 엔진이 앵커별 상태를 주지 않아 "좌표가 나오면 전부 수신, 아니면 아직 모름" 으로만
     * 답한다. 모르는 것을 고장으로 칠하지 않는다 — [missing] 은 항상 비어 있다.
     *
     * ⚠️ 0.2 에서 data class → 일반 class 로 바뀐다(감사 SP-C6): 공개 data class 는 필드를 하나 더해도 copy·componentN
     * 이 바뀌어 바이너리 호환이 깨진다. copy()·구조 분해(val (a, b) = …)에 기대지 말고 이름으로 읽는다. 같은 값을
     * 두 번 싣는 [matched]·[canPosition] 은 0.2 에서 지운다.
     */
    public data class AnchorDiagnostic(
        /** 콘솔에 등록된 로케이터 주소(오름차순) — [apply] (config) 로 받은 것. */
        public val registered: List<Int>,
        /** 신호가 잡힌 로케이터 — 좌표가 나오고 있으면 [registered] 전부, 아니면 빈 목록. */
        public val received: List<Int>,
        /** 등록 ∩ 수신 — [received] 와 같다. */
        @Deprecated("0.2 에서 제거 — received 와 같다", ReplaceWith("received"), level = DeprecationLevel.WARNING)
        public val matched: List<Int>,
        /** 등록됐는데 신호가 없는 주소 — 엔진이 특정할 수 없어 항상 비어 있다. */
        public val missing: List<Int>,
        /** 지금 좌표가 있는가([latestPosition] 이 있는가). */
        public val hasFix: Boolean,
        /** 한 줄 요약(엔진 상태 · 층 · 좌표 수 · 등록 로케이터 수) — 현재 언어. */
        public val summary: String,
    ) {
        /** 측위 가능한가 — [hasFix] 와 같다. */
        @Deprecated("0.2 에서 제거 — hasFix 와 같다", ReplaceWith("hasFix"), level = DeprecationLevel.WARNING)
        public val canPosition: Boolean get() = hasFix
    }

    override var delegate: PositioningProviderDelegate? = null

    // MARK: - 훅 (앱용 — 전부 메인 스레드)

    /** 로컬 구역 이벤트(진입·이탈·체류) — 서버 전송과 무관하게 호스트 UI 가 즉시 반응한다. */
    @Volatile public var onZoneEvent: ZoneEventListener? = null

    /** 엔진 로그 — [note] 로 넣은 줄도 여기로 온다. */
    @Volatile public var onLog: DebugLogListener? = null

    /** 층 추적 시작(층 번호) / 종료(`null`) — 호스트가 층 자동 선택에 쓴다. */
    @Volatile public var onFloorDetected: FloorDetectedListener? = null

    /** 엔진 오류 — 엔진 원본 번호·문장 그대로. */
    @Volatile public var onEngineError: EngineErrorListener? = null

    /**
     * 엔진이 준 **원본** 영역 이벤트 — 콘솔 구역으로 옮기기 전 그대로. 측위 가동 여부와 무관하게 온다
     * (일시정지 중에는 오지 않는다). [onZoneEvent] 와 나란히 놓고 이름·시점을 대조할 때 쓴다.
     */
    @Volatile public var onRawAreaEvent: RawAreaEventListener? = null

    /** 관찰 상태가 바뀌었다 — Java 용. Kotlin 은 `…Flow` 를 모은다. */
    @Volatile public var onChange: ProviderChangeListener? = null

    // MARK: - SDK 내부 연결 (FloorSession 이 건다 — 앱 훅과 따로 둔다)

    /** FloorSession 리스너(onZoneEnter/Exit/Dwell)로 가는 길. 앱의 [onZoneEvent] 를 덮지 않으려고 따로 둔다. */
    @Volatile internal var sessionZoneSink: ((ZoneEvent) -> Unit)? = null

    /** OneS1ght.onDebugLog 로 가는 길. 앱의 [onLog] 를 덮지 않으려고 따로 둔다. */
    @Volatile internal var sessionLogSink: ((LogLevel, String) -> Unit)? = null

    /** FloorSession.onFloorDetected 로 가는 길. 앱의 [onFloorDetected] 를 덮지 않으려고 따로 둔다. */
    @Volatile internal var sessionFloorSink: ((Long?) -> Unit)? = null

    // MARK: - 부품 (감사 SP-C1 — 이 클래스는 공개 파사드, 일은 internal 부품이 한다)
    //   EngineStateMachine  엔진 단계·가동·일시정지·재시작(SP-C3: reloading 이중 플래그를 흡수)
    //   EngineSession       엔진 기동·정지·정지 감시·세대(늦은 콜백 거르기)
    //   FloorTracker        콘솔 층 ↔ 엔진 층 대조(E3008)·층 미탐지 감시(E3007)·서버에 실을 층 ID
    //   ProviderStateStore  관찰 상태(…Flow)·화면 로그
    //   HubError            엔진 오류 번호표(SP-C4)
    //   UwbAreaJudge        엔진 영역 이름 → 콘솔 구역·체류

    private val scope = CoroutineScope(SupervisorJob() + main)
    private val judge = UwbAreaJudge(dwellScheduler ?: CoroutineDwellScheduler(scope))
    private val machine = EngineStateMachine(
        openSession = { launchEngine() },
        closeSession = { stopEngine() },
        log = { level, msg -> log(level, msg) },
    )
    private val store = ProviderStateStore(onChanged = { notifyChanged() })
    private val floors = FloorTracker(scope, store, report = { code, ctx -> report(code, ctx) })

    /** 엔진 콜백(코어 디스패처 · 지금 세대만) → 처리. */
    private val engineEvents = object : HubEngine.Listener {
        override fun onStarted() = handleStarted()
        override fun onStopped() = handleStopped()
        override fun onTrackingStarted(floorId: Long) = handleTrackingStarted(floorId)
        override fun onTrackingStopped(floorId: Long) = handleTrackingStopped(floorId)
        override fun onPosition(floorId: Long, x: Double, y: Double, z: Double) = handlePosition(floorId, x, y, z)
        override fun onAreaEvent(floorId: Long, areaName: String, inOut: String) = handleAreaEvent(floorId, areaName, inOut)
        override fun onError(code: Int, message: String) = handleError(code, message)
    }
    private val session = EngineSession(
        engine = engine,
        scope = scope,
        sink = engineEvents,
        afterCallback = { publish() },
        log = { level, msg -> log(level, msg) },
    )

    /**
     * 엔진 라이선스 키. 콘솔 `/config` 의 측위 키를 FloorSession 이 넣어 준다.
     * 비어 있으면 시작하지 않고 E1007 로 알린다 (조용한 실패 금지).
     */
    internal var license: String
        get() = session.license
        set(value) {
            session.license = value
        }

    /** 콘솔 로케이터 주소 — 엔진이 앵커를 자기 서버에서 받으므로 진단 '등록' 기준으로만 쓴다. */
    private var registeredAddresses: List<Int> = emptyList()

    /** 이번 가동에서 좌표가 한 번이라도 나왔는가 — 수신 점검(hasFix) 기준. 일시정지와 무관하다. */
    private var producedFix = false

    /** 이번 가동에서 소비한 좌표 수. */
    private var fixCount = 0

    /** 마지막 좌표 — 가동 중·일시정지 아님일 때만 있다. */
    private var lastPosition: Coordinates? = null

    /** 엔진 버전 안내는 한 번만. */
    private var announced = false

    // MARK: - 관찰 상태
    //
    // 스레드·값 계약(감사 SP-C7): 모든 멤버는 메인 스레드에서 부르고, 훅도 메인 스레드에서 불린다. 훅 등록만은 아무
    // 스레드에서 해도 된다(@Volatile). 게터 중 [isRunning]·[isPaused] 는 상태 기계의 **지금 값**이고, 나머지 게터와
    // 모든 `…Flow` 는 공개 동작·엔진 콜백 하나를 처리한 끝에 옮긴 **사본**이다 — 훅 안에서 읽으면 처리 중의 값이 아니라
    // 직전 사본이 보일 수 있다.

    /** 엔진 상태 흐름 — [phase] 의 StateFlow 판. */
    public val phaseFlow: StateFlow<PositioningPhase> = store.phase.asStateFlow()

    /** 측위 가동 흐름 — [isRunning] 의 StateFlow 판. */
    public val isRunningFlow: StateFlow<Boolean> = store.isRunning.asStateFlow()

    /** 일시정지 흐름 — [isPaused] 의 StateFlow 판. */
    public val isPausedFlow: StateFlow<Boolean> = store.isPaused.asStateFlow()

    /** 마지막 좌표 흐름 — [latestPosition] 의 StateFlow 판. */
    public val latestPositionFlow: StateFlow<Coordinates?> = store.latestPosition.asStateFlow()

    /** 엔진이 추적 중인 층 흐름 — [detectedFloorId] 의 StateFlow 판. */
    public val detectedFloorIdFlow: StateFlow<Long?> = store.detectedFloorId.asStateFlow()

    /** 좌표 수 흐름 — [measurementCount] 의 StateFlow 판. */
    public val measurementCountFlow: StateFlow<Int> = store.measurementCount.asStateFlow()

    /** 화면 로그 흐름 — [log] 의 StateFlow 판. */
    public val logFlow: StateFlow<List<String>> = store.log.asStateFlow()

    /** 엔진 상태 — [PositioningPhase.IDLE] 이 아니면 엔진이 돌고 있다. */
    public val phase: PositioningPhase get() = store.phase.value

    /** 상태 기계의 단계 그대로 — 테스트·내부 판정용([phase] 는 publish 시점의 사본). */
    internal val enginePhase: Phase get() = machine.phase

    /** 엔진이 돌고 있는가(탐색 중이든 추적 중이든). */
    public val isDetecting: Boolean
        get() = phase != PositioningPhase.IDLE && phase != PositioningPhase.STOPPING

    /** 측위 가동 중인가 — 좌표를 표시·수집·판정하고 있는가(일시정지 중에도 true). */
    public val isRunning: Boolean get() = machine.isRunning

    /**
     * 일시정지 중인가 — **엔진은 계속 돈다**(층·앵커를 붙들고 있어야 재개가 즉시 된다).
     * 멈추는 것은 좌표의 소비(표시·수집·판정)뿐이다.
     */
    override val isPaused: Boolean get() = machine.isPaused

    /** 마지막 좌표(도면 로컬 미터). 측위 전·일시정지 중·층을 놓친 뒤에는 `null`. */
    public val latestPosition: Coordinates? get() = store.latestPosition.value

    /** 엔진이 지금 추적 중인 층(공간 서비스 층 번호). `null` = 층 탐색 중. */
    public val detectedFloorId: Long? get() = store.detectedFloorId.value

    /** 이번 가동에서 받은 좌표 수(가동 중만 센다). */
    public val measurementCount: Int get() = store.measurementCount.value

    /** 화면 로그 — 최근 200줄. */
    public val log: List<String> get() = store.log.value

    /**
     * 프로토콜용 진단 — 코어(SessionCoordinator)가 읽어 로그 코드로 남긴다.
     *
     * ⚠️ `canAttributePerAnchor = false` 를 반드시 실어 보낸다. 이 값을 빼면 코어의 수신 점검이
     *    `missing.isEmpty`·`matched >= 3` 두 조건에 걸려 **영원히 아무것도 보고하지 않는다**.
     */
    override val positioningDiagnostic: PositioningDiagnostic
        get() {
            val registered = registeredAddresses.size
            val received = if (producedFix) registered else 0
            return PositioningDiagnostic(
                registeredCount = registered,
                receivedCount = received,
                matchedCount = received,
                missingAddresses = emptyList(), // 엔진은 미수신을 특정할 수 없다
                hasFix = producedFix,
                canAttributePerAnchor = false,
            )
        }

    /** 로케이터 단위 진단(화면용). 엔진 한계 — [AnchorDiagnostic] 참고. */
    public val diagnostic: AnchorDiagnostic
        get() {
            val fix = latestPosition != null
            val summary = SdkLocalized.t(
                "uwb.diag",
                phase.name.lowercase(),
                detectedFloorId?.toString() ?: "-",
                measurementCount,
                registeredAddresses.size,
            )
            return AnchorDiagnostic(
                registered = registeredAddresses,
                received = if (fix) registeredAddresses else emptyList(),
                matched = if (fix) registeredAddresses else emptyList(),
                missing = emptyList(),
                hasFix = fix,
                summary = summary,
            )
        }

    init {
        judge.onEvent = { handleZoneEvent(it) }
        judge.onLog = { level, msg -> log(level, msg) }
        judge.onReport = { code, ctx -> report(code, ctx) }
    }

    // MARK: - 로그

    /** 외부(호스트) 로그 합류 — 앱 로그를 같은 스트림([log]·[onLog])에 끼운다. */
    @MainThread
    public fun note(message: String) {
        log(LogLevel.LOG, message)
    }

    /** [note] 의 등급 지정 판. */
    @MainThread
    public fun note(level: LogLevel, message: String) {
        log(level, message)
    }

    // MARK: - 설정 주입

    /**
     * 콘솔 건물·층 ID — 코어(applyFloorStateToProvider)가 넣는다. 층 지정 해제(setFloorMap(null))면 빈 값이
     * 온다 — 그러면 E3008 대조를 멈추고, 엔진 층이 없을 때의 귀속도 하지 않는다. 가동 중에 불러도 안전하다.
     */
    @MainThread
    override fun apply(buildingId: String, floorId: String) {
        floors.applyConsoleFloor(buildingId, floorId) // 대조 기준이 바뀌었다 — 새 층 기준으로 다시 한 번 알린다
        log(LogLevel.LOG, SdkLocalized.t("provider.configApply", floorId.take(8)))
    }

    /**
     * 콘솔 로케이터·세션·존 — 그 층의 전부다.
     * · 앵커·세션은 엔진이 자기 서버에서 받으므로 **주입해도 측위에 쓰이지 않는다** — 진단('등록' 기준)용.
     *   빈 앵커는 「등록 없음」이다(층 해제 포함) — 예전엔 「안 바뀜」으로 보고 무시해 층을 해제해도 옛 등록 수가
     *   남았다(감사 SP-C9). 구역만 바꾸는 갱신은 [applyZones] 로 온다.
     * · 존은 zone_id 매핑용으로 판정기에 꽂는다. 빈 목록도 "없다"는 뜻이라 그대로 반영한다
     *   (구역을 전부 지운 상황이 전달되지 않으면 사라진 구역에서 시책이 계속 발화한다).
     */
    @MainThread
    override fun apply(config: PositioningConfig) {
        registeredAddresses = config.anchors.keys.sorted()
        judge.apply(config.zones)
        log(LogLevel.LOG, SdkLocalized.t("uwb.zonesApply", config.zones.size, config.anchors.size))
    }

    /** 구역만 바꾼다 — 등록 로케이터(진단 기준)는 그대로 둔다. */
    @MainThread
    override fun applyZones(zones: List<Zone>) {
        judge.apply(zones)
        log(LogLevel.LOG, SdkLocalized.t("uwb.zonesApply", zones.size, registeredAddresses.size))
    }

    /**
     * 엔진이 지오펜스를 다시 읽게 한다 — **껐다 켜는 것 말고는 방법이 없다.**
     *
     * 엔진 공개 API 는 setListener·start·stop·버전뿐이라 영역만 갱신할 길이 없다. 엔진은 start 때
     * 자기 서버에서 지오펜스를 한 번 읽고 그대로 물고 간다. 다시 뜨는 동안 좌표가 끊기고 층을
     * BLE 로 다시 찾는다 — 가동·일시정지 상태는 그대로 둔다(호스트에는 아무 일 없던 것처럼 보인다).
     */
    @MainThread
    override fun reloadGeofences() {
        // 측위 중이 아니면 할 일이 없다 — 다음 start 가 어차피 새로 읽는다.
        if (!machine.isRunning || machine.phase == Phase.IDLE || machine.phase == Phase.STOPPING) return
        log(LogLevel.WARN, SdkLocalized.t("uwb.geofenceReload"))
        machine.restart()
        publish()
    }

    // MARK: - 엔진 기동 (측위 시작과 분리)

    /**
     * 엔진만 띄워 층부터 찾는다. 좌표는 아직 쓰지 않는다 — 층이 잡히면 [onFloorDetected] 로 알리고,
     * 측위 가동은 그 뒤 `FloorSession.begin(provider)`(또는 [start])가 한다. 엔진이 이미 돌고 있으면 아무것도 안 한다.
     *
     * 라이선스는 SDK 가 콘솔에서 받은 값을 쓴다 — `OneS1ght.initialize` 뒤에 부른다(전이면 E1007 로 끝난다).
     */
    @MainThread
    public fun startDetection() {
        if (machine.phase != Phase.IDLE) return
        if (license.isBlank()) license = OneS1ght.coordinatorRef?.positioningLicense.orEmpty()
        announceOnce()
        machine.openDetection()
        afterStartAttempt(freshStart = false)
        publish()
    }

    /** 엔진을 완전히 멈춘다. 측위 중이었으면 그것도 끝난다. */
    @MainThread
    public fun stopDetection() {
        if (machine.phase == Phase.IDLE || machine.phase == Phase.STOPPING) return
        val wasRunning = machine.isRunning
        floors.cancelWatch()
        machine.stop()
        clearPosition()
        if (wasRunning) log(LogLevel.INFO, SdkLocalized.t("uwb.positioningOff", fixCount))
        publish()
    }

    // MARK: - 수명주기

    /** 측위 가동 — 코어가 begin 에서 부른다. 엔진이 아직 안 돌고 있으면 여기서 띄운다. */
    @MainThread
    override fun start() {
        if (machine.isRunning) return
        announceOnce()
        fixCount = 0
        producedFix = false
        floors.resetMismatchWarning()
        clearPosition()
        judge.reset()
        machine.start()
        afterStartAttempt(freshStart = true)
        publish()
    }

    /**
     * 측위 종료 — 좌표 표시·수집·판정을 끄고 엔진도 함께 멈춘다. 측위가 꺼져 있으면(엔진만 도는
     * [startDetection] 상태 포함) 예약된 시작만 지우고 엔진은 그대로 둔다 — 엔진까지 끄려면 [stopDetection].
     */
    @MainThread
    override fun stop() {
        if (!machine.isRunning) {
            machine.cancelPendingStart()
            return
        }
        floors.cancelWatch()
        machine.stop()
        clearPosition()
        log(LogLevel.INFO, SdkLocalized.t("uwb.positioningOff", fixCount))
        publish()
    }

    /**
     * 지금 안에 있는 구역에서 나간 것으로 친다(EXIT) — 코어가 앱이 배경으로 내려가 측위를 멈추기 **직전에** 부른다.
     *
     * 왜: 배경에서는 UWB 가 멈춰 엔진이 OUT 을 주지 않고, 복귀하면 판정기가 처음부터 시작해 같은 구역의 ENTER 가
     * EXIT 없이 두 번 서버로 갔다(쿠폰 중복 여지 — 감사 SP-B15). 일시정지 중이면 보내지 않는다(이벤트를 막는 중).
     */
    internal fun exitActiveZoneBeforeBackground() {
        if (!machine.acceptsPosition()) return
        judge.exitActive(clock())
    }

    /** 좌표·영역 이벤트 소비만 멈춘다 — **엔진은 계속 돈다**(층·앵커를 다시 찾지 않도록). */
    @MainThread
    override fun pause() {
        machine.pause()
        if (machine.isPaused) clearPosition() // 마지막 점을 살아 있는 것처럼 두지 않는다
        publish()
    }

    /**
     * 일시정지 해제. 판정기를 초기화한다 — 멈춘 동안 들어온 영역 이벤트를 버렸으므로, 안에 있을 때
     * 멈추고 밖으로 걸어 나온 뒤 재개하면 판정기는 아직 "안에 있다" 고 믿는다.
     */
    @MainThread
    override fun resume() {
        if (machine.resume()) judge.reset()
        publish()
    }

    // MARK: - 상태 기계 콜백

    /** 엔진 기동 — [EngineSession.open]. 결과는 콜백으로 온다(동기 실패는 [afterStartAttempt] 가 꺼낸다). */
    private fun launchEngine() {
        session.open(
            onMissingLicense = { onEngineError?.onEngineError(HubError.LICENSE_MISSING.code, "license not set") },
            beforeStart = { floors.setDetected(null) },
        )
    }

    /** 엔진 정지 — onStopped 가 [EngineSession.STOP_TIMEOUT_MS] 안에 안 오면 멈춘 것으로 친다. */
    private fun stopEngine() {
        session.close(
            stillStopping = { machine.phase == Phase.STOPPING },
            onTimeout = {
                finishStopped()
                publish()
            },
        )
    }

    /**
     * 상태 기계가 start 를 처리한 직후(직접 start 든, 정지 뒤 이어받은 start 든) 부른다.
     * 동기 실패면 코드를 올리고 IDLE 로 되돌린다. 새로 가동됐으면 시작 로그·층 감시·입장 트리거.
     * 정지 중이라 예약만 됐으면 아무것도 하지 않는다.
     */
    private fun afterStartAttempt(freshStart: Boolean) {
        session.takeOpenFailure()?.let { (code, context) ->
            report(code, context)
            val wasRunning = machine.isRunning
            finishStopped()
            // 라이선스 없음(E1007)·권한(SecurityException)은 사람이 풀어야 한다. 그 밖의 동기 예외는 다시 켜 볼 만하다.
            if (wasRunning) notifyUnexpectedStop("engine did not start ($context)", retryable = code == SdkErrorCode.UWB_SESSION_FAILED)
            return
        }
        if (!machine.isRunning || !freshStart) return
        log(LogLevel.INFO, SdkLocalized.t("uwb.positioningOn", detectedFloorId?.toString() ?: "-"))
        floors.startWatch(isRunning = { machine.isRunning }, phaseName = { machine.phase.name.lowercase() })
        // 입장 트리거 — 통지만 한다(코어는 이걸로 아무것도 하지 않는다 — 건물·층 조회는 앱의 몫).
        delegate?.onEnter(this, floors.buildingId)
    }

    // MARK: - 엔진 콜백 (코어 디스패처에서 — EngineSession 이 지금 세대의 것만 넘긴다)

    private fun handleStarted() {
        machine.onOpened()
        log(LogLevel.INFO, SdkLocalized.t("uwb.started"))
    }

    private fun handleStopped() {
        if (machine.phase == Phase.IDLE) return
        // 시작 중에는 onStopped 가 올 일이 없다 — 엔진은 시작 단계 실패를 onError 로만 알리고(onStopped 없음),
        // onStopped 는 우리가 stop 을 부른 뒤(STOPPING)에만 보낸다. 여기 오는 것은 앞 기동의 늦은 정지 통지가
        // 새 리스너에 닿은 것이다(엔진 싱글턴이라 리스너 교체 뒤에도 옛 통지가 올 수 있다) — 받으면 방금 띄운
        // 측위를 스스로 멈춘 것으로 오인한다.
        if (machine.phase == Phase.STARTING) {
            log(LogLevel.LOG, SdkLocalized.t("uwb.stopped") + " (stale — ignored while starting)")
            return
        }
        val requested = machine.phase == Phase.STOPPING // stop()·재적재가 부른 정지
        if (requested) {
            log(LogLevel.LOG, SdkLocalized.t("uwb.stopped"))
        } else {
            // 오류 3·7·10 등 뒤의 자동 정지 — provider 는 스스로 다시 켜지 않는다. 코어에 알려(가동 중이었으면)
            // 코어가 다시 켜 보거나 세션을 닫는다(감사 SP-B1).
            log(LogLevel.WARN, SdkLocalized.t("uwb.stoppedSelf"))
        }
        val wasRunning = machine.isRunning
        finishStopped()
        if (!requested && wasRunning) {
            val last = session.lastHubError
            notifyUnexpectedStop("engine stopped itself (last error ${last ?: "-"})", isRetryable(last))
        }
    }

    /**
     * 켜 달라고 했는데(측위 가동 중) 엔진이 꺼졌다 — 코어에 알린다. 다시 켤지·세션을 닫을지는 코어가 정한다.
     * 상태를 다 정리한 뒤(finishStopped·publish 전) 부른다 — 코어가 곧바로 stop/start 해도 어긋나지 않게.
     */
    private fun notifyUnexpectedStop(context: String, retryable: Boolean) {
        publish()
        delegate?.onStoppedUnexpectedly(this, retryable, context)
    }

    /**
     * 엔진이 내려갔다 — 정리 후 상태 기계에 알린다. 재적재 중이었으면 상태 기계가 곧바로 다시 띄우고
     * (가동·일시정지 유지), 예약된 start 가 있었으면 이어받는다.
     */
    private fun finishStopped() {
        session.cancelStopWatchdog()
        producedFix = false
        // 우리가 껐다 켜는 재시작이면 층 상실을 앱에 알리지 않는다(곧바로 다시 찾는다).
        val restarting = machine.isRestarting
        if (detectedFloorId != null) {
            floors.setDetected(null)
            if (!restarting) emitFloorDetected(null)
        }
        val after = machine.onClosed()
        if (!machine.isRunning) clearPosition()
        if (machine.phase == Phase.IDLE) {
            floors.cancelWatch()
            session.releaseListener() // 정지 직후가 아니라 여기(onStopped 뒤)서 해제한다
            return
        }
        // 재시작이면 시작 로그·층 감시·입장 트리거를 다시 내지 않는다 — 플래그 대신 상태 기계의 반환값으로(SP-C3).
        afterStartAttempt(freshStart = after != EngineStateMachine.AfterClose.RESTARTED)
    }

    private fun handleTrackingStarted(fid: Long) {
        machine.onTrackingStarted()
        floors.setDetected(fid)
        floors.cancelWatch() // 층을 찾았다 — 미탐지 감시 해제
        log(LogLevel.INFO, SdkLocalized.t("uwb.trackingStart", fid))
        floors.checkAgreement(fid)
        emitFloorDetected(fid)
    }

    private fun handleTrackingStopped(fid: Long) {
        machine.onTrackingStopped()
        floors.setDetected(null)
        clearPosition()
        log(LogLevel.INFO, SdkLocalized.t("uwb.trackingStop", fid))
        emitFloorDetected(null)
    }

    private fun handlePosition(fid: Long, x: Double, y: Double, z: Double) {
        // 측위 중이 아니면(탐색만 하는 중, 정지 중 늦게 온 좌표) 버린다 — 화면에도, 서버에도 안 간다.
        if (!machine.isRunning) return
        producedFix = true
        // 일시정지 중에도 엔진은 좌표를 계속 준다. 소비하는 자리에서 버린다 — 엔진을 끄지 않는
        // 것이 일시정지의 요점이라(층·앵커 유지).
        if (!machine.acceptsPosition()) return
        fixCount += 1
        val coordinates = Coordinates(x, y, z)
        lastPosition = coordinates
        // 좌표 라인은 로그에서 제외 — 초당 여러 건이라 판정 이벤트를 묻어버린다.
        // 존 판정은 엔진이 한다 — 여기서 좌표를 판정기에 넣지 않는다(onAreaEvent 로 들어온다).
        delegate?.onPosition(this, coordinates, fid.toString(), clock())
    }

    /** 엔진이 올린 영역 전환. */
    internal fun handleAreaEvent(fid: Long, name: String, inOut: String) {
        // 일시정지 중에는 여기서 끝난다 — pause() 는 "화면의 내 위치·서버 전송·존 판정을 멈춘다" 고
        // 약속한다(iOS 2026-09-10 회귀: 좌표에만 가드가 있어 진입·이탈 알림이 계속 떴다).
        // 엔진은 계속 돌고 있어 판정 자체는 들어온다 — 그 사실만 로그에 남긴다.
        if (machine.isPaused) {
            log(LogLevel.LOG, SdkLocalized.t("uwb.areaPaused", inOut, name))
            return
        }
        val now = clock()
        log(LogLevel.INFO, SdkLocalized.t("uwb.area", inOut, name, fid))
        onRawAreaEvent?.onRawAreaEvent(fid, name, inOut, now) // 원본 그대로 — 진단용
        // 가동 중이 아니면 여기서 끝. 수집·전송은 측위 세션 안에서만 한다.
        if (!machine.isRunning) return
        judge.handleAreaEvent(inOut, name, now)
    }

    /**
     * 엔진 오류. 화면 로그에 더해 E-코드로도 올린다 — 관리자는 콘솔 로그 분석기에서 이걸 본다.
     *
     * 시작 단계(onStarted 전)의 실패는 onStopped 가 오지 않는다 — 여기서 되돌린다.
     * 2(이미 시작됨)는 엔진이 이미 돌고 있다는 뜻이라 시작된 것으로 치고, 8(정지 중)은 그 정지가
     * 끝나는 대로 다시 띄운다(재시작 경로 — onStopped 가 이어서 온다).
     */
    private fun handleError(code: Int, message: String) {
        session.lastHubError = code
        log(LogLevel.ERROR, SdkLocalized.t("uwb.error", code, message, describe(code)))
        onEngineError?.onEngineError(code, message)
        sdkCode(code, message)?.let { report(it, "engine=$code $message") }
        // 시작 직후 끈 경우(STOPPING) — 시작이 접혔으면 onStopped 는 오지 않는다. 감시 타이머 5초를 기다리지 않고
        // 곧바로 멈춘 것으로 친다(감사 SP-B14: 끄고 바로 다시 켜면 5초 늦게 켜졌다).
        val hub = HubError.of(code)
        if (machine.phase == Phase.STOPPING && hub?.abortsStart == true) {
            finishStopped()
            return
        }
        if (machine.phase != Phase.STARTING) return
        when {
            hub?.abortsStart == true -> {
                val wasRunning = machine.isRunning // 층 탐색만 하던 엔진(startDetection)이면 코어는 모른다
                finishStopped()
                if (wasRunning) notifyUnexpectedStop("engine=$code at start", isRetryable(code))
            }
            hub == HubError.ALREADY_RUNNING -> machine.onOpened()
            hub == HubError.START_WHILE_STOPPING -> {
                // 시작 로그·층 감시·입장 트리거는 첫 시도에서 이미 나갔다 — 재시도는 재적재처럼 조용히.
                // 층 탐색 전용(startDetection)이어도 다시 연다(SP-B4). 재시작 표시는 상태 기계가 든다(SP-C3).
                machine.retryOpenAfterStop()
            }
        }
    }

    /**
     * ZoneEvent → 밖으로. 서버 전송은 IN/OUT 만, DWELL 은 훅(FloorSession 리스너·[onZoneEvent])까지만.
     * (서버 시책 매칭이 dwell 시책을 IN 에도 태우므로 같이 보내면 중복 발급 여지가 있다)
     */
    private fun handleZoneEvent(event: ZoneEvent) {
        if (machine.isPaused || !machine.isRunning) return
        log(LogLevel.LOG, "🎯 ${event.label}")
        sessionZoneSink?.invoke(event)
        onZoneEvent?.onZoneEvent(event)
        val status = when (event) {
            is ZoneEvent.Enter -> ZoneEventStatus.ENTER
            is ZoneEvent.Exit -> ZoneEventStatus.EXIT
            is ZoneEvent.Dwell -> return // 온디바이스 전용
        }
        delegate?.onZone(this, event.zone.id, status, floors.currentFloorId(), event.at)
    }

    // MARK: - 헬퍼

    /** 층 탐지·상실 → 앱 훅([onFloorDetected])과 세션 리스너(FloorSession.onFloorDetected) 둘 다. */
    private fun emitFloorDetected(fid: Long?) {
        onFloorDetected?.onFloorDetected(fid)
        sessionFloorSink?.invoke(fid)
    }

    private fun clearPosition() {
        lastPosition = null
    }

    private fun announceOnce() {
        if (announced) return
        announced = true
        val version = session.version
        val supported = session.hardwareAvailable
        log(
            LogLevel.LOG,
            SdkLocalized.t("uwb.ready", version, SdkLocalized.t(if (supported) "uwb.supported" else "uwb.unsupported")),
        )
    }

    private fun report(code: SdkErrorCode, context: String) {
        delegate?.onReport(this, code, context)
    }

    private fun log(level: LogLevel, msg: String) {
        store.appendLog(msg)
        sessionLogSink?.invoke(level, msg)
        onLog?.onLog(level, msg)
        notifyChanged()
    }

    /**
     * 상태 기계·내부 값 → 관찰 상태. 바뀐 것이 있으면 [onChange] 를 한 번 부른다.
     * 공개 동작·엔진 콜백 하나를 처리한 끝에서 부른다(중간 상태를 밖에 보이지 않도록).
     */
    private fun publish() {
        store.publish(
            phase = machine.phase,
            isRunning = machine.isRunning,
            isPaused = machine.isPaused,
            latestPosition = if (machine.acceptsPosition()) lastPosition else null,
            measurementCount = fixCount,
        )
    }

    private fun notifyChanged() {
        onChange?.onChanged(this)
    }

    public companion object {
        /**
         * 이 기기가 측위를 할 수 있는가 — Android 17(API 37) 이상 && UWB 칩 있음. `OneS1ght.initialize` 전에도
         * 쓸 수 있다(Context 를 직접 받는다). 판정은 `OneS1ght.deviceAvailability == AVAILABLE` 과 같다.
         * 칩은 있어도 DL-TDoA 를 못 하는 드문 기기는 여기서 가를 수 없다 — 시작 뒤 E2002 로 남는다.
         */
        @JvmStatic
        public fun isSupported(context: Context): Boolean =
            AndroidDeviceCapability(contextProvider = { context.applicationContext ?: context }).hasUwbHardware()

        /** 층 미탐지 감시 시간 — iOS 와 같다. */
        internal const val FLOOR_DETECT_DELAY_MS: Long = 20_000L

        /** 정지 요청 뒤 onStopped 를 기다리는 최대 시간 — [EngineSession.STOP_TIMEOUT_MS]. */
        internal const val STOP_TIMEOUT_MS: Long = EngineSession.STOP_TIMEOUT_MS

        /** 화면 로그 보관 줄 수 — iOS 와 같다. */
        internal const val LOG_CAPACITY: Int = 200

        /**
         * 시작 단계에서 엔진이 스스로 되돌리는(onStopped 없이 끝나는) 오류 번호 — [HubError.abortsStart] 의 번호판.
         */
        internal val START_ABORT_CODES: Set<Int> = HubError.entries.filter { it.abortsStart }.map { it.code }.toSet()

        /**
         * 엔진이 스스로 멈춘 뒤 다시 켜 볼 만한가 — 마지막 오류를 모르면(null·모르는 번호) 그렇다고 본다.
         * 사람이 풀어야 하는 번호는 [HubError.needsPerson] 이 정한다.
         */
        internal fun isRetryable(hubError: Int?): Boolean = hubError?.let { HubError.of(it) }?.needsPerson != true

        /** 측위 엔진 오류 코드 → SDK E-코드. 표는 [HubError.sdkCode] 한 곳에 있다(감사 SP-C4 · iOS K15). */
        internal fun sdkCode(hubError: Int, message: String = ""): SdkErrorCode? = HubError.of(hubError)?.sdkCode(message)

        /** 측위 엔진 오류 코드표(1~13) — 로그 문구. */
        internal fun describe(code: Int): String =
            if (code in 1..13) SdkLocalized.t("uwb.err$code") else SdkLocalized.t("uwb.errUnknown")

        /** 모듈 내부 팩토리 — @JvmSynthetic 이라 Java 에는 안 보인다. */
        @JvmSynthetic
        internal fun create(
            engine: HubEngine,
            main: CoroutineDispatcher,
            clock: () -> Long,
            dwellScheduler: DwellScheduler? = null,
        ): UwbPositioningProvider = UwbPositioningProvider(engine, main, dwellScheduler, clock)

        /**
         * 공개 생성자의 엔진 — API 37 이상이면 실제 엔진, 아니면 엔진 클래스를 건드리지 않는 자리표시자.
         * 게이트는 여기서 직접 `Build.VERSION.SDK_INT` 로 본다(lint NewApi 가 확인한다).
         */
        private fun realEngine(context: Context): HubEngine =
            if (Build.VERSION.SDK_INT >= MIN_POSITIONING_SDK) IntelligenceHubEngine(context) else UnavailableHubEngine()
    }
}

/**
 * 내장 provider 생성 — FloorSession 의 기본 `builtInProviderFactory` 가 부른다.
 *
 * [main] 은 코어 상태를 바꾸는 단일 디스패처(운영: `Dispatchers.Main.immediate`)여야 한다 —
 * 엔진 콜백과 DWELL 타이머가 모두 이 디스패처로 넘어온다.
 */
@JvmSynthetic
@RequiresApi(MIN_POSITIONING_SDK)
internal fun createBuiltInProvider(
    context: Context,
    main: CoroutineDispatcher,
    clock: () -> Long,
): UwbPositioningProvider = UwbPositioningProvider.create(IntelligenceHubEngine(context), main, clock)
