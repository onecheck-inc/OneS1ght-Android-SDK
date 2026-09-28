package co.onecheck.ones1ght.android.positioning

//
//  UwbPositioningProvider.kt
//  실측위 어댑터 (내장) — 레인징 세션 + 좌표 엔진 + ZoneEngine.
//
//  UWB 전파 수신 → 좌표 계산 → 존 판정까지 하고, 결과를 PositioningProvider 콜백으로
//  SDK 코어에 흘려보낸다(spec §5):
//
//    apply(config) ─► 앵커 → 좌표 엔진 · 진단 등록 기준 / 존 → ZoneEngine
//    start ─► RangingEngine.open(sessionId)
//              onAnchorSeen(addr) ─► AnchorTracker (진단)
//              onPosition(x,y,z)  ─► 메인 ─► delegate.onPosition + ZoneEngine.ingest
//    ZoneEngine.onEvent ─► IN/OUT: delegate.onZone + onZoneEvent / DWELL: onZoneEvent 만
//
//  android.* 는 [RangingEngine] 뒤(`UwbRangingEngine`)로 몰았다 — 이 클래스의 오케스트레이션
//  (상태 기계·진단·판정 연결·오류 매핑·일시정지)은 JVM 단위테스트로 검증한다.
//
//  스레드: 엔진 콜백은 엔진 스레드에서 오고, 전부 주입된 메인 디스패처로 넘긴 뒤 처리한다.
//  콜백마다 세션 세대([generation])를 달아, 닫힌 세션의 늦은 콜백이 새 세션을 건드리지 않게 한다.
//
//  포팅 원본: b804f3b UwbPositioningProvider.swift(세션 흐름·진단·좌표 전달),
//  현재 UwbPositioningProvider.swift(pause·phase·startAfterStop·로그 키).
//

import android.content.Context
import co.onecheck.ones1ght.android.model.Coordinates
import co.onecheck.ones1ght.android.model.Position
import co.onecheck.ones1ght.android.model.ZoneEvent
import co.onecheck.ones1ght.android.model.ZoneEventStatus
import co.onecheck.ones1ght.android.runtime.LogLevel
import co.onecheck.ones1ght.android.runtime.SdkErrorCode
import co.onecheck.ones1ght.android.runtime.SdkLocalized
import co.onecheck.ones1ght.android.zone.CoroutineDwellScheduler
import co.onecheck.ones1ght.android.zone.ZoneEngine
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 내장 UWB(DL-TDoA) 측위 제공자. 고객 앱이 직접 만들지 않는다 — FloorSession 이
 * [createBuiltInProvider] 로 만들어 쓴다.
 */
// 생성자는 private — internal 이면 JVM 에선 public 이라 Java 에 CoroutineDispatcher·Function0 이 드러난다.
// 모듈 안에서는 [create]/[createBuiltInProvider] 로 만든다.
public class UwbPositioningProvider private constructor(
    private val engine: RangingEngine,
    private val zoneEngine: ZoneEngine,
    main: CoroutineDispatcher,
    private val clock: () -> Long,
) : PositioningProvider {

    override var delegate: PositioningProviderDelegate? = null

    /** 로컬 zone 이벤트 훅(IN/OUT/DWELL) — 내부 호출자 전용(고객 콜백은 FloorSession 리스너). */
    internal var onZoneEvent: ((ZoneEvent) -> Unit)? = null

    /** 엔진 내부 로그 훅 — 표준 경로에서 onDebugLog 로 이어진다. */
    internal var onLog: ((LogLevel, String) -> Unit)? = null

    private val scope = CoroutineScope(SupervisorJob() + main)
    private val tracker = AnchorTracker()
    private val machine = EngineStateMachine(
        openSession = { openSession() },
        closeSession = { closeSession() },
        log = { level, msg -> log(level, msg) },
    )

    private var buildingId = ""
    private var floorId = ""

    /** 층별 UWB 세션(networkIdentifier) — apply(config) 로 주입. 없으면 start 를 거부한다. */
    private var sessionId: Int? = null

    /**
     * 이번 세션에서 좌표가 한 번이라도 나왔는가 — 수신 점검(hasFix) 기준.
     * 일시정지와 무관하다: 멈춘 동안에도 세션은 좌표를 내고 있고, 그걸 "좌표 없음"으로
     * 보고하면 E4002 오탐이 된다.
     */
    private var producedFix = false

    /** STOPPING 감시 — onClosed 가 끝내 안 오면 여기서 닫힘으로 친다. */
    private var stopWatchdog: Job? = null

    /** 이번 세션에서 소비한 좌표 수(진단·종료 로그용). */
    private var fixCount = 0

    /** 세션 세대 — 열 때마다 1 증가. 엔진 스레드가 읽으므로 volatile. */
    @Volatile
    private var generation = 0

    private var diagnosticJob: Job? = null

    /** openSession 안에서 동기적으로 실패한 사유 — 상태 기계가 start 를 끝낸 뒤 정리한다. */
    private var pendingOpenFailure: Pair<SdkErrorCode, String>? = null

    /** sessionId 변경으로 우리가 닫았다 다시 여는 중인가 — 입장 트리거를 다시 쏘지 않는다. */
    private var reopening = false

    internal val phase: Phase get() = machine.phase
    internal val isRunning: Boolean get() = machine.isRunning
    override val isPaused: Boolean get() = machine.isPaused

    override val positioningDiagnostic: PositioningDiagnostic?
        get() = tracker.diagnostic(hasFix = producedFix)

    init {
        zoneEngine.onEvent = { handleZoneEvent(it) }
        zoneEngine.onLog = { level, msg -> log(level, msg) }
    }

    // MARK: - 설정 주입

    /** 콘솔 건물·층 — 좌표·존 이벤트에 실을 floorId. */
    override fun apply(buildingId: String, floorId: String) {
        this.buildingId = buildingId
        this.floorId = floorId
    }

    /**
     * 앵커·세션·존 주입.
     * · 앵커가 비면 이전 값을 유지한다(b804f3b 와 같음). 세션 ID 도 null 이면 유지.
     * · 존은 빈 목록도 "없다" 는 뜻이라 그대로 반영한다.
     * · 측위 중에 세션 ID 가 바뀌면 세션을 닫고 새 번호로 다시 연다.
     */
    override fun apply(config: PositioningConfig) {
        if (config.anchors.isNotEmpty()) {
            engine.applyAnchors(config.anchors)
            tracker.register(config.anchors.keys)
        }
        val newSessionId = config.sessionId
        if (newSessionId != null && newSessionId != sessionId) {
            sessionId = newSessionId
            if (machine.isRunning) {
                // 가동·일시정지 상태를 유지한 채 세션만 갈아 끼운다(onClosed 뒤 새 번호로 연다).
                reopening = true
                machine.restart()
            }
        }
        zoneEngine.apply(config.zones)
    }

    // MARK: - 수명주기

    override fun start() {
        if (machine.isRunning) return
        if (sessionId == null) {
            // 세션 ID 미주입 = 측위 불가 — 하드코딩 폴백 없음. phase 는 IDLE 그대로.
            report(SdkErrorCode.SESSION_ID_MISSING, "floor=${floorLabel()}")
            return
        }
        machine.start()
        afterStartAttempt(enterBuilding = true)
    }

    override fun stop() {
        val wasRunning = machine.isRunning
        reopening = false
        machine.stop()
        diagnosticJob?.cancel()
        diagnosticJob = null
        producedFix = false
        if (wasRunning) log(LogLevel.INFO, SdkLocalized.t("uwb.positioningOff", fixCount))
    }

    /** 좌표 소비만 멈춘다 — 세션은 계속 돈다(앵커를 다시 찾지 않도록). */
    override fun pause() {
        machine.pause()
    }

    /** 일시정지 해제 — 멈춘 동안 버린 이벤트 때문에 판정기가 옛 상태를 물지 않도록 reset. */
    override fun resume() {
        if (machine.resume()) zoneEngine.reset()
    }

    /** 존 목록은 apply(config) 로 판정기에 곧바로 들어간다 — 엔진 재시작이 필요 없다(spec §6). */
    override fun reloadGeofences() {}

    // MARK: - 상태 기계 콜백

    private fun openSession() {
        val sid = sessionId ?: run {
            pendingOpenFailure = SdkErrorCode.SESSION_ID_MISSING to "floor=${floorLabel()}"
            return
        }
        generation += 1
        fixCount = 0
        producedFix = false
        tracker.clearSeen()
        zoneEngine.reset()
        try {
            engine.open(sid, listenerFor(generation))
        } catch (e: SecurityException) {
            pendingOpenFailure = SdkErrorCode.PERMISSION_DENIED to "open: ${e.message}"
        } catch (e: UnsupportedOperationException) {
            pendingOpenFailure = SdkErrorCode.DEVICE_NOT_SUPPORTED to "open: ${e.message}"
        } catch (e: RuntimeException) {
            pendingOpenFailure = SdkErrorCode.UWB_SESSION_FAILED to "open: ${e.javaClass.simpleName} ${e.message}"
        }
    }

    private fun closeSession() {
        diagnosticJob?.cancel()
        diagnosticJob = null
        armStopWatchdog()
        engine.close()
    }

    /**
     * 닫기 요청 뒤 [STOP_TIMEOUT_MS] 안에 onClosed 가 안 오면 닫힌 것으로 친다. 이게 없으면
     * STOPPING 에 영원히 머물고 이후 start 는 예약만 되어 조용히 측위가 안 켜진다
     * (iOS 0.1.22~0.1.23 과 같은 부류). 세대를 올려 옛 세션의 늦은 콜백은 버린다.
     */
    private fun armStopWatchdog() {
        stopWatchdog?.cancel()
        stopWatchdog = scope.launch {
            delay(STOP_TIMEOUT_MS)
            stopWatchdog = null
            if (machine.phase != Phase.STOPPING) return@launch
            generation += 1
            log(LogLevel.WARN, SdkLocalized.t("uwb.stopped") + " (timeout ${STOP_TIMEOUT_MS}ms)")
            finishClosed()
        }
    }

    /**
     * 상태 기계가 start 를 처리한 직후(직접 start 든, 정지 뒤 이어받은 start 든) 부른다.
     * 동기 실패면 코드를 올리고 IDLE 로 되돌린다. 실제로 가동됐으면 시작 로그·입장 트리거·
     * 5초 진단을 건다. 정지 중이라 예약만 됐으면 아무것도 하지 않는다.
     */
    private fun afterStartAttempt(enterBuilding: Boolean) {
        pendingOpenFailure?.let { (code, context) ->
            pendingOpenFailure = null
            report(code, context)
            machine.onClosed()
            return
        }
        if (!machine.isRunning) return
        log(LogLevel.INFO, SdkLocalized.t("uwb.positioningOn", floorLabel()))
        if (enterBuilding) delegate?.onEnter(this, buildingId)
        scheduleDiagnostic()
    }

    // MARK: - 엔진 콜백 (메인에서)

    private fun listenerFor(gen: Int): RangingEngine.Listener = object : RangingEngine.Listener {
        override fun onOpened() = onMain(gen) { machine.onOpened() }
        override fun onOpenFailed(reason: Int) = onMain(gen) { handleOpenFailed(reason) }
        override fun onClosed(reason: Int) = onMain(gen) { handleClosed(reason) }
        override fun onPosition(x: Double, y: Double, z: Double) = onMain(gen) { handlePosition(x, y, z) }
        override fun onAnchorSeen(address: Int) {
            if (gen == generation) tracker.seen(address) // 동기화된 추적기 — 메인으로 넘기지 않는다
        }
    }

    private fun onMain(gen: Int, block: () -> Unit) {
        scope.launch { if (gen == generation) block() }
    }

    private fun handleOpenFailed(reason: Int) {
        if (machine.phase == Phase.IDLE) return
        val requested = machine.phase == Phase.STOPPING
        val code = RangingErrorMapping.code(reason, security = false)
        log(if (requested) LogLevel.LOG else LogLevel.ERROR, errorLine(reason, code))
        // 사용자가(또는 우리가) 끈 세션은 어떤 사유로 닫혀도 오류 코드가 아니다.
        if (!requested) code?.let { report(it, "openFailed reason=$reason") }
        finishClosed()
    }

    private fun handleClosed(reason: Int) {
        if (machine.phase == Phase.IDLE) return
        val requested = machine.phase == Phase.STOPPING
        val code = RangingErrorMapping.code(reason, security = false)
        if (requested) {
            log(LogLevel.LOG, SdkLocalized.t("uwb.stopped"))
        } else {
            log(LogLevel.WARN, SdkLocalized.t("uwb.stoppedSelf"))
            log(LogLevel.ERROR, errorLine(reason, code))
        }
        if (!requested) code?.let { report(it, "closed reason=$reason") }
        finishClosed()
    }

    /** 세션이 내려갔다 — 정리 후 상태 기계에 알리고, 예약된 start 가 있었으면 이어받는다. */
    private fun finishClosed() {
        stopWatchdog?.cancel()
        stopWatchdog = null
        diagnosticJob?.cancel()
        diagnosticJob = null
        producedFix = false
        val enter = !reopening
        reopening = false
        machine.onClosed()
        if (machine.isRunning || pendingOpenFailure != null) afterStartAttempt(enterBuilding = enter)
    }

    private fun handlePosition(x: Double, y: Double, z: Double) {
        if (!machine.isRunning) return
        // phase 는 엔진 상태 — 일시정지와 무관하게 첫 좌표에서 추적으로 넘어간다.
        if (machine.phase == Phase.STARTING || machine.phase == Phase.SEARCHING) machine.onFirstFix()
        producedFix = true
        // 일시정지 중에도 세션은 좌표를 계속 준다. 소비하는 자리에서 버린다.
        if (!machine.acceptsPosition()) return
        val coordinates = Coordinates(x, y, z)
        fixCount += 1
        val now = clock()
        delegate?.onPosition(this, coordinates, floorIdOrNull(), now)
        zoneEngine.ingest(Position(x, y), now)
    }

    /**
     * ZoneEngine 이벤트 → 밖으로. 서버 전송은 IN/OUT 만, DWELL 은 onZoneEvent(앱 내 훅)까지만.
     *
     * 일시정지 중에는 여기서 끝난다 — 좌표 경로만 막고 이벤트 경로를 빠뜨리면 「내 위치 표시
     * 중지」 중에도 진입·이탈 알림이 계속 뜬다(iOS 2026-09-10 회귀). 넘긴 사실은 로그로 남긴다.
     */
    private fun handleZoneEvent(event: ZoneEvent) {
        val status = when (event) {
            is ZoneEvent.Enter -> ZoneEventStatus.ENTER
            is ZoneEvent.Exit -> ZoneEventStatus.EXIT
            is ZoneEvent.Dwell -> ZoneEventStatus.DWELL
        }
        if (machine.isPaused) {
            log(LogLevel.LOG, SdkLocalized.t("uwb.areaPaused", status.wire, event.zone.name))
            return
        }
        if (!machine.isRunning) return
        onZoneEvent?.invoke(event)
        if (status != ZoneEventStatus.DWELL) {
            delegate?.onZone(this, event.zone.id, status, floorIdOrNull(), event.at)
        }
    }

    // MARK: - 진단

    /**
     * 통신 진단 — 시작 5초 뒤 1회(b804f3b). 측위 가능(좌표 있음 또는 유효 앵커 3대 이상)이면
     * INFO, 아니면 ERROR. 앵커별 E4003/E4002 판정은 코어가 [positioningDiagnostic] 으로 한다.
     */
    private fun scheduleDiagnostic() {
        diagnosticJob?.cancel()
        diagnosticJob = scope.launch {
            delay(DIAGNOSTIC_DELAY_MS)
            if (!machine.isRunning) return@launch
            val d = tracker.diagnostic(hasFix = producedFix)
            val canPosition = d.hasFix || d.matchedCount >= 3
            log(
                if (canPosition) LogLevel.INFO else LogLevel.ERROR,
                SdkLocalized.t(
                    "uwb.diag",
                    machine.phase.name.lowercase(),
                    floorLabel(),
                    fixCount,
                    d.registeredCount,
                ),
            )
        }
    }

    // MARK: - 헬퍼

    private fun errorLine(reason: Int, code: SdkErrorCode?): String =
        SdkLocalized.t(
            "uwb.error",
            reason,
            code?.code ?: "-",
            code?.summary ?: SdkLocalized.t("uwb.errUnknown"),
        )

    private fun floorIdOrNull(): String? = floorId.ifEmpty { null }

    private fun floorLabel(): String = floorId.take(8).ifEmpty { "-" }

    private fun report(code: SdkErrorCode, context: String) {
        delegate?.onReport(this, code, context)
    }

    private fun log(level: LogLevel, msg: String) {
        onLog?.invoke(level, msg)
    }

    internal companion object {
        const val DIAGNOSTIC_DELAY_MS: Long = 5_000L

        /** 모듈 내부 팩토리 — @JvmSynthetic 이라 Java 에는 안 보인다. */
        @JvmSynthetic
        fun create(
            engine: RangingEngine,
            zoneEngine: ZoneEngine,
            main: CoroutineDispatcher,
            clock: () -> Long,
        ): UwbPositioningProvider = UwbPositioningProvider(engine, zoneEngine, main, clock)

        /** 닫기 요청 뒤 onClosed 를 기다리는 최대 시간. */
        const val STOP_TIMEOUT_MS: Long = 3_000L
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
): UwbPositioningProvider {
    val zoneEngine = ZoneEngine(CoroutineDwellScheduler(CoroutineScope(SupervisorJob() + main)))
    return UwbPositioningProvider.create(UwbRangingEngine(context.applicationContext), zoneEngine, main, clock)
}
