package co.onecheck.ones1ght.android.positioning

//
//  UwbPositioningProvider.kt
//  실측위 어댑터 (내장) — 통합 측위 엔진을 감싼다.
//
//  역할 분담 (iOS 0.1.23 과 같다):
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
//  포팅 원본: UwbPositioningProvider.swift (iOS 0.1.23 — 통합 엔진 경로).
//

import android.content.Context
import co.onecheck.ones1ght.android.model.Coordinates
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 내장 UWB 측위 제공자. 고객 앱이 직접 만들지 않는다 — FloorSession 이
 * [createBuiltInProvider] 로 만들어 쓴다.
 */
// 생성자는 private — internal 이면 JVM 에선 public 이라 Java 에 CoroutineDispatcher·Function0 이 드러난다.
// 모듈 안에서는 [create]/[createBuiltInProvider] 로 만든다.
public class UwbPositioningProvider private constructor(
    private val engine: HubEngine,
    main: CoroutineDispatcher,
    dwellScheduler: DwellScheduler?,
    private val clock: () -> Long,
) : PositioningProvider {

    override var delegate: PositioningProviderDelegate? = null

    /** 로컬 zone 이벤트 훅(IN/OUT/DWELL) — 내부 호출자 전용(고객 콜백은 FloorSession 리스너). */
    internal var onZoneEvent: ((ZoneEvent) -> Unit)? = null

    /** 엔진 내부 로그 훅 — 표준 경로에서 onDebugLog 로 이어진다. */
    internal var onLog: ((LogLevel, String) -> Unit)? = null

    /** 층 추적 시작(층 번호)/종료(null) — 내부 진단용. */
    internal var onFloorDetected: ((Long?) -> Unit)? = null

    /** 엔진이 준 **원본** 영역 이벤트 — 콘솔 존으로 옮기기 전 그대로(진단·테스트용). */
    internal var onRawAreaEvent: ((floorId: Long, areaName: String, inOut: String, atMs: Long) -> Unit)? = null

    /**
     * 엔진 라이선스 키. 콘솔 `/config` 의 측위 키를 FloorSession 이 넣어 준다.
     * 비어 있으면 시작하지 않고 E1007 로 알린다 (조용한 실패 금지).
     */
    internal var license: String = ""

    private val scope = CoroutineScope(SupervisorJob() + main)
    private val judge = UwbAreaJudge(dwellScheduler ?: CoroutineDwellScheduler(scope))
    private val machine = EngineStateMachine(
        openSession = { launchEngine() },
        closeSession = { stopEngine() },
        log = { level, msg -> log(level, msg) },
    )

    private var buildingId = ""

    /** apply(buildingId, floorId) 로 받은 콘솔 층 ID — 대조(E3008)와 엔진 층이 없을 때의 귀속용. */
    private var floorId = ""

    /** 콘솔 로케이터 수 — 엔진이 앵커를 자기 서버에서 받으므로 진단 '등록' 기준으로만 쓴다. */
    private var registeredLocators = 0

    /** 엔진이 지금 추적 중인 층(공간 서비스 층 번호). null = 층 탐색 중. */
    internal var detectedFloorId: Long? = null
        private set

    /** 이번 가동에서 좌표가 한 번이라도 나왔는가 — 수신 점검(hasFix) 기준. 일시정지와 무관하다. */
    private var producedFix = false

    /** 이번 가동에서 소비한 좌표 수(종료 로그용). */
    private var fixCount = 0

    /** 층 불일치는 층마다 한 번만 알린다 — 층이 유지되는 동안 반복하면 로그가 덮인다. */
    private var warnedFloorMismatch: Long? = null

    /** 층 미탐지 감시(E3007). */
    private var floorWatch: Job? = null

    /** STOPPING 감시 — onStopped 가 끝내 안 오면 여기서 멈춘 것으로 친다. */
    private var stopWatchdog: Job? = null

    /** 기동 세대 — 엔진을 띄울 때마다 1 증가. 엔진 스레드가 읽으므로 volatile. */
    @Volatile
    private var generation = 0

    /** launchEngine 안에서 동기적으로 실패한 사유 — 상태 기계가 start 를 끝낸 뒤 정리한다. */
    private var pendingOpenFailure: Pair<SdkErrorCode, String>? = null

    /** 구역 재적재로 우리가 껐다 켜는 중인가 — 입장 트리거·층 감시를 다시 걸지 않는다. */
    private var reloading = false

    /** 엔진 버전 안내는 한 번만. */
    private var announced = false

    internal val phase: Phase get() = machine.phase
    internal val isRunning: Boolean get() = machine.isRunning
    override val isPaused: Boolean get() = machine.isPaused

    /**
     * 프로토콜용 진단 — 코어(SessionCoordinator)가 읽어 로그 코드로 남긴다.
     *
     * ⚠️ `canAttributePerAnchor = false` 를 반드시 실어 보낸다. 이 값을 빼면 코어의 수신 점검이
     *    `missing.isEmpty`·`matched >= 3` 두 조건에 걸려 **영원히 아무것도 보고하지 않는다**.
     */
    override val positioningDiagnostic: PositioningDiagnostic
        get() {
            val received = if (producedFix) registeredLocators else 0
            return PositioningDiagnostic(
                registeredCount = registeredLocators,
                receivedCount = received,
                matchedCount = received,
                missingAddresses = emptyList(), // 엔진은 미수신을 특정할 수 없다
                hasFix = producedFix,
                canAttributePerAnchor = false,
            )
        }

    init {
        judge.onEvent = { handleZoneEvent(it) }
        judge.onLog = { level, msg -> log(level, msg) }
        judge.onReport = { code, ctx -> report(code, ctx) }
    }

    // MARK: - 설정 주입

    /** 콘솔 건물·층 ID — 코어(applyFloorStateToProvider)가 넣는다. */
    override fun apply(buildingId: String, floorId: String) {
        this.buildingId = buildingId
        this.floorId = floorId
        log(LogLevel.LOG, SdkLocalized.t("provider.configApply", floorId.take(8)))
    }

    /**
     * 콘솔 로케이터·세션·존.
     * · 앵커·세션은 엔진이 자기 서버에서 받으므로 **주입해도 측위에 쓰이지 않는다** — 진단용.
     * · 존은 zone_id 매핑용으로 판정기에 꽂는다. 빈 목록도 "없다"는 뜻이라 그대로 반영한다
     *   (구역을 전부 지운 상황이 전달되지 않으면 사라진 구역에서 시책이 계속 발화한다).
     */
    override fun apply(config: PositioningConfig) {
        if (config.anchors.isNotEmpty()) registeredLocators = config.anchors.size
        judge.apply(config.zones)
        log(LogLevel.LOG, SdkLocalized.t("uwb.zonesApply", config.zones.size, config.anchors.size))
    }

    /**
     * 엔진이 지오펜스를 다시 읽게 한다 — **껐다 켜는 것 말고는 방법이 없다.**
     *
     * 엔진 공개 API 는 setListener·start·stop·버전뿐이라 영역만 갱신할 길이 없다. 엔진은 start 때
     * 자기 서버에서 지오펜스를 한 번 읽고 그대로 물고 간다. 다시 뜨는 동안 좌표가 끊기고 층을
     * BLE 로 다시 찾는다 — 가동·일시정지 상태는 그대로 둔다(호스트에는 아무 일 없던 것처럼 보인다).
     */
    override fun reloadGeofences() {
        // 측위 중이 아니면 할 일이 없다 — 다음 start 가 어차피 새로 읽는다.
        if (!machine.isRunning || machine.phase == Phase.IDLE || machine.phase == Phase.STOPPING) return
        log(LogLevel.WARN, SdkLocalized.t("uwb.geofenceReload"))
        reloading = true
        machine.restart()
    }

    // MARK: - 수명주기

    /** 측위 가동 — 코어가 begin 에서 부른다. 엔진이 아직 안 돌고 있으면 여기서 띄운다. */
    override fun start() {
        if (machine.isRunning) return
        announceOnce()
        fixCount = 0
        producedFix = false
        warnedFloorMismatch = null
        judge.reset()
        machine.start()
        afterStartAttempt(freshStart = true)
    }

    /** 측위 종료 — 좌표 표시·수집·판정을 끄고 엔진도 함께 멈춘다. */
    override fun stop() {
        val wasRunning = machine.isRunning
        reloading = false
        cancelFloorWatch()
        machine.stop()
        if (wasRunning) log(LogLevel.INFO, SdkLocalized.t("uwb.positioningOff", fixCount))
    }

    /** 좌표·영역 이벤트 소비만 멈춘다 — **엔진은 계속 돈다**(층·앵커를 다시 찾지 않도록). */
    override fun pause() {
        machine.pause()
    }

    /**
     * 일시정지 해제. 판정기를 초기화한다 — 멈춘 동안 들어온 영역 이벤트를 버렸으므로, 안에 있을 때
     * 멈추고 밖으로 걸어 나온 뒤 재개하면 판정기는 아직 "안에 있다" 고 믿는다.
     */
    override fun resume() {
        if (machine.resume()) judge.reset()
    }

    // MARK: - 상태 기계 콜백

    /** 엔진 기동 — 라이선스 등록 → 리스너(새 세대) → start. 결과는 콜백으로 온다. */
    private fun launchEngine() {
        val key = license.trim()
        if (key.isEmpty()) {
            log(LogLevel.ERROR, SdkLocalized.t("uwb.noLicense"))
            pendingOpenFailure = SdkErrorCode.KEY_UNAVAILABLE to "reason=engine_license_empty"
            return
        }
        generation += 1
        detectedFloorId = null
        try {
            engine.setLicense(key)
            engine.setListener(listenerFor(generation))
            log(LogLevel.INFO, SdkLocalized.t("uwb.starting", "****"))
            engine.start()
        } catch (e: SecurityException) {
            pendingOpenFailure = SdkErrorCode.PERMISSION_DENIED to "start: ${e.message}"
        } catch (e: RuntimeException) {
            pendingOpenFailure = SdkErrorCode.UWB_SESSION_FAILED to "start: ${e.javaClass.simpleName} ${e.message}"
        }
    }

    private fun stopEngine() {
        armStopWatchdog()
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
     * 오류 8 을 받고, 그 정지가 끝나는 대로 다시 띄운다 — [handleError]).
     */
    private fun armStopWatchdog() {
        stopWatchdog?.cancel()
        stopWatchdog = scope.launch {
            delay(STOP_TIMEOUT_MS)
            stopWatchdog = null
            if (machine.phase != Phase.STOPPING) return@launch
            generation += 1
            log(LogLevel.WARN, SdkLocalized.t("uwb.stopped") + " (timeout ${STOP_TIMEOUT_MS}ms)")
            finishStopped()
        }
    }

    /**
     * 상태 기계가 start 를 처리한 직후(직접 start 든, 정지 뒤 이어받은 start 든) 부른다.
     * 동기 실패면 코드를 올리고 IDLE 로 되돌린다. 새로 가동됐으면 시작 로그·층 감시·입장 트리거.
     * 정지 중이라 예약만 됐으면 아무것도 하지 않는다.
     */
    private fun afterStartAttempt(freshStart: Boolean) {
        pendingOpenFailure?.let { (code, context) ->
            pendingOpenFailure = null
            report(code, context)
            reloading = false
            finishStopped()
            return
        }
        if (!machine.isRunning || !freshStart) return
        log(LogLevel.INFO, SdkLocalized.t("uwb.positioningOn", detectedFloorId?.toString() ?: "-"))
        startFloorWatch()
        // 입장 트리거 — SDK 가 buildings/floors 로드를 시작하게
        delegate?.onEnter(this, buildingId)
    }

    // MARK: - 엔진 콜백 (코어 디스패처에서)

    private fun listenerFor(gen: Int): HubEngine.Listener = object : HubEngine.Listener {
        override fun onStarted() = onCore(gen) { handleStarted() }
        override fun onStopped() = onCore(gen) { handleStopped() }
        override fun onTrackingStarted(floorId: Long) = onCore(gen) { handleTrackingStarted(floorId) }
        override fun onTrackingStopped(floorId: Long) = onCore(gen) { handleTrackingStopped(floorId) }
        override fun onPosition(floorId: Long, x: Double, y: Double, z: Double) =
            onCore(gen) { handlePosition(floorId, x, y, z) }
        override fun onAreaEvent(floorId: Long, areaName: String, inOut: String) =
            onCore(gen) { handleAreaEvent(floorId, areaName, inOut) }
        override fun onError(code: Int, message: String) = onCore(gen) { handleError(code, message) }
    }

    private fun onCore(gen: Int, block: () -> Unit) {
        scope.launch { if (gen == generation) block() }
    }

    private fun handleStarted() {
        machine.onOpened()
        log(LogLevel.INFO, SdkLocalized.t("uwb.started"))
    }

    private fun handleStopped() {
        if (machine.phase == Phase.IDLE) return
        val requested = machine.phase == Phase.STOPPING // stop()·재적재가 부른 정지
        if (requested) {
            log(LogLevel.LOG, SdkLocalized.t("uwb.stopped"))
        } else {
            // 오류 3·7·10 등 뒤의 자동 정지 — 자동 재개 없음. 앱이 "다시 시작" 을 안내한다.
            log(LogLevel.WARN, SdkLocalized.t("uwb.stoppedSelf"))
        }
        finishStopped()
    }

    /**
     * 엔진이 내려갔다 — 정리 후 상태 기계에 알린다. 재적재 중이었으면 상태 기계가 곧바로 다시 띄우고
     * (가동·일시정지 유지), 예약된 start 가 있었으면 이어받는다.
     */
    private fun finishStopped() {
        stopWatchdog?.cancel()
        stopWatchdog = null
        producedFix = false
        val wasReloading = reloading
        reloading = false
        if (detectedFloorId != null) {
            detectedFloorId = null
            if (!wasReloading) onFloorDetected?.invoke(null)
        }
        machine.onClosed()
        if (machine.phase == Phase.IDLE) {
            cancelFloorWatch()
            engine.setListener(null) // 정지 직후가 아니라 여기(onStopped 뒤)서 해제한다
            return
        }
        afterStartAttempt(freshStart = !wasReloading)
    }

    private fun handleTrackingStarted(fid: Long) {
        machine.onTrackingStarted()
        detectedFloorId = fid
        cancelFloorWatch() // 층을 찾았다 — 미탐지 감시 해제
        log(LogLevel.INFO, SdkLocalized.t("uwb.trackingStart", fid))
        checkFloorAgreement(fid)
        onFloorDetected?.invoke(fid)
    }

    /**
     * 엔진이 잡은 층 ↔ 콘솔이 지정한 층 대조.
     *
     * 서버로 나가는 `floor_id` 는 엔진 값이다. 두 값이 어긋난 채로 두면 좌표·존 이벤트가 콘솔이
     * 모르는 층에 쌓여, 화면에서는 "데이터가 없다"로만 보인다. 콘솔 층이 아직 안 정해졌으면
     * (setFloorMap 전) 대조하지 않는다 — 그건 불일치가 아니다.
     */
    private fun checkFloorAgreement(fid: Long) {
        if (floorId.isEmpty() || floorId == fid.toString()) return
        if (warnedFloorMismatch == fid) return
        warnedFloorMismatch = fid
        report(SdkErrorCode.FLOOR_ID_MISMATCH, "engine=$fid console=$floorId")
    }

    private fun handleTrackingStopped(fid: Long) {
        machine.onTrackingStopped()
        detectedFloorId = null
        log(LogLevel.INFO, SdkLocalized.t("uwb.trackingStop", fid))
        onFloorDetected?.invoke(null)
    }

    private fun handlePosition(fid: Long, x: Double, y: Double, z: Double) {
        // 측위 중이 아니면(정지 중 늦게 온 좌표) 버린다 — 화면에도, 서버에도 안 간다.
        if (!machine.isRunning) return
        producedFix = true
        // 일시정지 중에도 엔진은 좌표를 계속 준다. 소비하는 자리에서 버린다 — 엔진을 끄지 않는
        // 것이 일시정지의 요점이라(층·앵커 유지).
        if (!machine.acceptsPosition()) return
        fixCount += 1
        // 좌표 라인은 로그에서 제외 — 초당 여러 건이라 판정 이벤트를 묻어버린다.
        // 존 판정은 엔진이 한다 — 여기서 좌표를 판정기에 넣지 않는다(onAreaEvent 로 들어온다).
        delegate?.onPosition(this, Coordinates(x, y, z), fid.toString(), clock())
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
        onRawAreaEvent?.invoke(fid, name, inOut, now) // 원본 그대로 — 진단용
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
        log(LogLevel.ERROR, SdkLocalized.t("uwb.error", code, message, describe(code)))
        sdkCode(code)?.let { report(it, "engine=$code $message") }
        if (machine.phase != Phase.STARTING) return
        when (code) {
            in START_ABORT_CODES -> finishStopped()
            HUB_ALREADY_STARTED -> machine.onOpened()
            HUB_STOPPING -> {
                // 시작 로그·층 감시·입장 트리거는 첫 시도에서 이미 나갔다 — 재시도는 재적재처럼 조용히.
                reloading = true
                machine.restart()
            }
        }
    }

    /**
     * ZoneEvent → 밖으로. 서버 전송은 IN/OUT 만, DWELL 은 onZoneEvent(앱 내 훅)까지만.
     * (서버 시책 매칭이 dwell 시책을 IN 에도 태우므로 같이 보내면 중복 발급 여지가 있다)
     */
    private fun handleZoneEvent(event: ZoneEvent) {
        if (machine.isPaused || !machine.isRunning) return
        log(LogLevel.LOG, "🎯 ${event.label}")
        onZoneEvent?.invoke(event)
        val status = when (event) {
            is ZoneEvent.Enter -> ZoneEventStatus.ENTER
            is ZoneEvent.Exit -> ZoneEventStatus.EXIT
            is ZoneEvent.Dwell -> return // 온디바이스 전용
        }
        delegate?.onZone(this, event.zone.id, status, currentFloorId(), event.at)
    }

    // MARK: - 층 미탐지 감시

    /**
     * 층 미탐지 감시 — 측위를 켰는데 [FLOOR_DETECT_DELAY_MS] 가 지나도록 층이 안 잡히면 E3007.
     * 엔진은 층을 못 찾아도 오류를 주지 않고 계속 탐색만 한다 — 앱에서는 "그냥 좌표가 안 나온다"
     * 로만 보여서, 이 감시가 없으면 BLE 미수신이 아무 흔적도 남기지 않는다.
     */
    private fun startFloorWatch() {
        cancelFloorWatch()
        if (detectedFloorId != null) return
        floorWatch = scope.launch {
            delay(FLOOR_DETECT_DELAY_MS)
            floorWatch = null
            if (!machine.isRunning || detectedFloorId != null) return@launch
            report(
                SdkErrorCode.FLOOR_NOT_DETECTED,
                "phase=${machine.phase.name.lowercase()} after=${FLOOR_DETECT_DELAY_MS / 1000}s",
            )
        }
    }

    private fun cancelFloorWatch() {
        floorWatch?.cancel()
        floorWatch = null
    }

    // MARK: - 헬퍼

    /** 서버에 실을 층 ID — 엔진이 잡은 층이 있으면 그 번호, 없으면 콘솔에서 주입받은 값. */
    private fun currentFloorId(): String? = detectedFloorId?.toString() ?: floorId.ifEmpty { null }

    private fun announceOnce() {
        if (announced) return
        announced = true
        val version = runCatching { engine.version }.getOrDefault("?")
        val supported = runCatching { engine.hardwareAvailable }.getOrDefault(false)
        log(
            LogLevel.LOG,
            SdkLocalized.t("uwb.ready", version, SdkLocalized.t(if (supported) "uwb.supported" else "uwb.unsupported")),
        )
    }

    private fun report(code: SdkErrorCode, context: String) {
        delegate?.onReport(this, code, context)
    }

    private fun log(level: LogLevel, msg: String) {
        onLog?.invoke(level, msg)
    }

    internal companion object {
        /** 층 미탐지 감시 시간 — iOS 와 같다. */
        const val FLOOR_DETECT_DELAY_MS: Long = 20_000L

        /** 정지 요청 뒤 onStopped 를 기다리는 최대 시간. */
        const val STOP_TIMEOUT_MS: Long = 5_000L

        private const val HUB_ALREADY_STARTED = 2
        private const val HUB_STOPPING = 8

        /**
         * 시작 단계에서 엔진이 스스로 되돌리는(onStopped 없이 끝나는) 오류 — 엔진 1.1.0 기준:
         * 1 라이선스 미등록 · 3 Bluetooth · 7 위치 · 9 설정 누락 · 10 라이선스 거부 · 11 서버 미도달 ·
         * 12 미지원 기기 · 13 스캔 과다.
         */
        val START_ABORT_CODES: Set<Int> = setOf(1, 3, 7, 9, 10, 11, 12, 13)

        /**
         * 측위 엔진 오류 코드 → SDK E-코드. `null` 은 "로그로만 남길 것" — 2(중복 start)·8(정지 중 start)은
         * 호출 순서 문제라 현장 진단 가치가 없고, 코드로 올리면 재시도마다 쌓여 진짜 오류를 덮는다.
         *
         * 13(BLE 스캔 시작이 너무 잦음 — 안드로이드가 앱당 스캔 시작 횟수를 제한한다)은 **E3007(층 미탐지)**
         * 로 올린다. 층은 BLE 스캔으로만 찾으므로 결과가 같다(층을 못 찾아 좌표가 안 나온다). 원인이 스캔
         * 제한이라는 사실은 문맥(`engine=13 …`)에 남는다 — 잠시 뒤 다시 시작하면 풀린다.
         */
        fun sdkCode(hubError: Int): SdkErrorCode? = when (hubError) {
            1 -> SdkErrorCode.INVALID_KEY // 라이선스 미등록
            3 -> SdkErrorCode.PERMISSION_DENIED // Bluetooth 불가(꺼짐·권한)
            4 -> SdkErrorCode.LOCATORS_MISSING // 그 층의 앵커 정보 없음
            5 -> SdkErrorCode.UWB_SESSION_FAILED // DL-TDoA 세션 오류
            6 -> SdkErrorCode.AREA_JUDGE_FAILED // 영역 판정 오류
            7 -> SdkErrorCode.PERMISSION_DENIED // 위치 불가(권한·정밀도·서비스 꺼짐)
            9 -> SdkErrorCode.PERMISSION_DENIED // 매니페스트 RANGING 선언 누락(권한 계열)
            10 -> SdkErrorCode.INVALID_KEY // 서버가 라이선스 거부
            11 -> SdkErrorCode.NETWORK // 라이선스 서버 미도달
            12 -> SdkErrorCode.DEVICE_NOT_SUPPORTED // DL-TDoA 미지원 기기(OS 미달 포함)
            13 -> SdkErrorCode.FLOOR_NOT_DETECTED // BLE 스캔 시작 제한 — 층 탐색 불가
            else -> null // 2 · 8 · 미지의 코드
        }

        /** 측위 엔진 오류 코드표(1~13) — 로그 문구. */
        fun describe(code: Int): String =
            if (code in 1..13) SdkLocalized.t("uwb.err$code") else SdkLocalized.t("uwb.errUnknown")

        /** 모듈 내부 팩토리 — @JvmSynthetic 이라 Java 에는 안 보인다. */
        @JvmSynthetic
        fun create(
            engine: HubEngine,
            main: CoroutineDispatcher,
            clock: () -> Long,
            dwellScheduler: DwellScheduler? = null,
        ): UwbPositioningProvider = UwbPositioningProvider(engine, main, dwellScheduler, clock)
    }
}

/**
 * 내장 provider 생성 — FloorSession 의 기본 `builtInProviderFactory` 가 부른다.
 *
 * [main] 은 코어 상태를 바꾸는 단일 디스패처(운영: `Dispatchers.Main.immediate`)여야 한다 —
 * 엔진 콜백과 DWELL 타이머가 모두 이 디스패처로 넘어온다.
 */
@JvmSynthetic
internal fun createBuiltInProvider(
    context: Context,
    main: CoroutineDispatcher,
    clock: () -> Long,
): UwbPositioningProvider = UwbPositioningProvider.create(IntelligenceHubEngine(context), main, clock)
