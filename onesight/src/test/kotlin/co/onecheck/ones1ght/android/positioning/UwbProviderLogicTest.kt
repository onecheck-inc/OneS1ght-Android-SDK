package co.onecheck.ones1ght.android.positioning

import co.onecheck.ones1ght.android.DebugLogListener
import co.onecheck.ones1ght.android.model.Coordinates
import co.onecheck.ones1ght.android.model.Position
import co.onecheck.ones1ght.android.model.Zone
import co.onecheck.ones1ght.android.model.ZoneEvent
import co.onecheck.ones1ght.android.model.ZoneEventStatus
import co.onecheck.ones1ght.android.runtime.LogLevel
import co.onecheck.ones1ght.android.runtime.SdkErrorCode
import co.onecheck.ones1ght.android.runtime.SdkLocalized
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestDispatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 측위 엔진 어댑터의 오케스트레이션 — 라이선스·기동/정지·층 추적·층 감시(E3007)·층 대조(E3008)·
 * 영역 매핑·일시정지·구역 재적재·오류 매핑·늦은 콜백.
 *
 * 실제 엔진은 [HubEngine] 뒤에 있고 여기서는 [FakeHubEngine] 으로 갈아 끼운다. 엔진 콜백은 엔진
 * 스레드에서 오므로 provider 는 코어 디스패처로 넘긴다 — 테스트는 [StandardTestDispatcher] 를 코어로
 * 주고 `runCurrent()` 로 흘린다.
 *
 * 포팅 원본: UwbPositioningProvider.swift(통합 엔진 경로), PositioningPauseTests.swift.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UwbProviderLogicTest {

    private class RecordingDelegate : PositioningProviderDelegate {
        val positions = mutableListOf<Pair<Coordinates, String?>>()
        val zones = mutableListOf<Triple<String, ZoneEventStatus, String?>>()
        val enters = mutableListOf<String>()
        val reports = mutableListOf<Pair<SdkErrorCode, String>>()

        override fun onPosition(provider: PositioningProvider, coordinates: Coordinates, floorId: String?, atMs: Long) {
            positions += coordinates to floorId
        }

        override fun onZone(provider: PositioningProvider, zoneId: String, status: ZoneEventStatus, floorId: String?, atMs: Long) {
            zones += Triple(zoneId, status, floorId)
        }

        override fun onEnter(provider: PositioningProvider, buildingId: String) {
            enters += buildingId
        }

        override fun onReport(provider: PositioningProvider, code: SdkErrorCode, context: String) {
            reports += code to context
        }

        fun codes(): List<SdkErrorCode> = reports.map { it.first }
    }

    private val sq = listOf(Position(0.0, 0.0), Position(10.0, 0.0), Position(10.0, 10.0), Position(0.0, 10.0))
    private val zoneA = Zone("za", "정육 코너", sq, dwellSeconds = 5)

    private lateinit var scheduler: TestCoroutineScheduler
    private lateinit var main: TestDispatcher
    private lateinit var engine: FakeHubEngine
    private lateinit var delegate: RecordingDelegate
    private lateinit var provider: UwbPositioningProvider
    private val zoneEvents = mutableListOf<ZoneEvent>()
    private val raw = mutableListOf<String>()
    private val logs = mutableListOf<Pair<LogLevel, String>>()

    @Before fun setUp() {
        SdkLocalized.language = "ko"
        scheduler = TestCoroutineScheduler()
        main = StandardTestDispatcher(scheduler)
        engine = FakeHubEngine()
        delegate = RecordingDelegate()
        provider = UwbPositioningProvider.create(engine, main, clock = { scheduler.currentTime })
        provider.delegate = delegate
        provider.license = "lic-0123456789"
        provider.onZoneEvent = ZoneEventListener { zoneEvents += it }
        provider.onRawAreaEvent = RawAreaEventListener { _, name, inOut, _ -> raw += "$inOut:$name" }
        provider.onLog = DebugLogListener { level, msg -> logs += level to msg }
    }

    @After fun tearDown() {
        provider.stop()
        SdkLocalized.language = null
    }

    private fun flush() = scheduler.runCurrent()

    private val hub: HubEngine.Listener get() = engine.current!!

    /** 콘솔 층 "14" + 존 A. */
    private fun configured(consoleFloor: String = "14") {
        provider.apply("b-1", consoleFloor)
        provider.apply(
            PositioningConfig(
                anchors = mapOf(0x0001 to doubleArrayOf(0.0, 0.0, 2.0), 0x0002 to doubleArrayOf(5.0, 0.0, 2.0)),
                sessionId = 7,
                zones = listOf(zoneA),
            ),
        )
    }

    /** 가동 + 엔진 시작 확인 + 층 14 추적까지. */
    private fun tracking(floor: Long = 14) {
        configured()
        provider.start()
        hub.onStarted()
        hub.onTrackingStarted(floor)
        flush()
    }

    // MARK: - 라이선스

    /** 라이선스가 없으면 엔진을 띄우지 않고 E1007 — 조용한 실패 금지. */
    @Test fun startWithoutLicenseIsRefusedWithE1007() {
        provider.license = "  "
        configured()

        provider.start()
        flush()

        assertEquals(0, engine.starts)
        assertTrue(engine.licenses.isEmpty())
        assertEquals(listOf(SdkErrorCode.KEY_UNAVAILABLE), delegate.codes())
        assertEquals(Phase.IDLE, provider.enginePhase)
        assertFalse(provider.isRunning)
        assertTrue("입장 트리거도 없다", delegate.enters.isEmpty())
    }

    /** 라이선스는 start 직전에 엔진에 등록한다(앞뒤 공백 제거). 로그에 키가 새지 않는다. */
    @Test fun startRegistersLicenseListenerThenStarts() {
        provider.license = "  lic-0123456789 "
        configured()

        provider.start()

        assertEquals(listOf("lic-0123456789"), engine.licenses)
        assertNotNull(engine.current)
        assertEquals(1, engine.starts)
        assertEquals(Phase.STARTING, provider.enginePhase)
        assertTrue(provider.isRunning)
        assertEquals(listOf("b-1"), delegate.enters)
        assertFalse("라이선스가 로그에 찍혔다", logs.any { it.second.contains("lic-0123") })

        hub.onStarted()
        flush()
        assertEquals(Phase.SEARCHING, provider.enginePhase)
    }

    // MARK: - 층

    @Test fun trackingStartedMovesToTrackingAndRemembersFloor() {
        tracking()

        assertEquals(Phase.TRACKING, provider.enginePhase)
        assertEquals(14L, provider.detectedFloorId)
        assertTrue("같은 층이면 불일치가 아니다", delegate.reports.isEmpty())

        hub.onTrackingStopped(14)
        flush()
        assertEquals(Phase.SEARCHING, provider.enginePhase)
        assertNull(provider.detectedFloorId)
    }

    /** 엔진 층 ≠ 콘솔 층이면 E3008 — 층이 유지되는 동안은 한 번만. */
    @Test fun floorMismatchIsReportedOncePerFloor() {
        configured(consoleFloor = "15")
        provider.start()
        hub.onStarted()
        hub.onTrackingStarted(14)
        hub.onTrackingStopped(14)
        hub.onTrackingStarted(14)
        flush()

        val mismatches = delegate.reports.filter { it.first == SdkErrorCode.FLOOR_ID_MISMATCH }
        assertEquals(1, mismatches.size)
        assertEquals("engine=14 console=15", mismatches[0].second)

        hub.onTrackingStarted(16)
        flush()
        assertEquals(2, delegate.reports.count { it.first == SdkErrorCode.FLOOR_ID_MISMATCH })
    }

    /** 콘솔 층을 안 정했으면(setFloorMap 전) 대조하지 않는다 — 그건 불일치가 아니다. */
    @Test fun noConsoleFloorMeansNoMismatch() {
        provider.start()
        hub.onStarted()
        hub.onTrackingStarted(14)
        flush()

        assertFalse(delegate.codes().contains(SdkErrorCode.FLOOR_ID_MISMATCH))
    }

    /** 20초 안에 층을 못 찾으면 E3007 — 엔진은 계속 탐색만 하고 오류를 주지 않는다. */
    @Test fun floorNotDetectedAfterTwentySeconds() {
        configured()
        provider.start()
        hub.onStarted()
        flush()

        scheduler.advanceTimeBy(UwbPositioningProvider.FLOOR_DETECT_DELAY_MS - 1)
        scheduler.runCurrent()
        assertFalse(delegate.codes().contains(SdkErrorCode.FLOOR_NOT_DETECTED))

        scheduler.advanceTimeBy(2)
        scheduler.runCurrent()
        assertEquals(1, delegate.codes().count { it == SdkErrorCode.FLOOR_NOT_DETECTED })
    }

    @Test fun floorFoundInTimeCancelsTheWatch() {
        tracking()
        scheduler.advanceTimeBy(UwbPositioningProvider.FLOOR_DETECT_DELAY_MS * 2)
        scheduler.runCurrent()

        assertFalse(delegate.codes().contains(SdkErrorCode.FLOOR_NOT_DETECTED))
    }

    @Test fun stopCancelsTheFloorWatch() {
        configured()
        provider.start()
        provider.stop()
        scheduler.advanceTimeBy(UwbPositioningProvider.FLOOR_DETECT_DELAY_MS * 2)
        scheduler.runCurrent()

        assertFalse(delegate.codes().contains(SdkErrorCode.FLOOR_NOT_DETECTED))
    }

    // MARK: - 좌표

    /** 좌표는 코어로 넘긴 뒤 나가고, 서버 floor_id 는 엔진 층이다(콘솔 층 아님). */
    @Test fun positionIsForwardedOnCoreWithEngineFloor() {
        configured(consoleFloor = "15")
        provider.start()
        hub.onStarted()
        hub.onTrackingStarted(14)

        hub.onPosition(14, 5.0, 5.0, 1.2)
        assertTrue("코어로 넘기기 전에는 아무것도 나가지 않는다", delegate.positions.isEmpty())
        flush()

        assertEquals(listOf(Coordinates(5.0, 5.0, 1.2) to "14"), delegate.positions)
        assertTrue(provider.positioningDiagnostic.hasFix)
    }

    /** 엔진은 앵커별 수신을 안 준다 — 진단은 반드시 "특정 불가" 로 나간다(그래야 E4002 가 산다). */
    @Test fun diagnosticCannotAttributePerAnchor() {
        configured()
        val before = provider.positioningDiagnostic
        assertFalse(before.canAttributePerAnchor)
        assertEquals(2, before.registeredCount)
        assertFalse(before.hasFix)
        assertTrue(before.missingAddresses.isEmpty())

        provider.start()
        hub.onPosition(14, 1.0, 1.0, 0.0)
        flush()
        val after = provider.positioningDiagnostic
        assertTrue(after.hasFix)
        assertEquals(2, after.receivedCount)
    }

    // MARK: - 영역

    /** 엔진 영역 이름 → 콘솔 zone_id 로 서버에 간다. floor_id 는 엔진 층. */
    @Test fun areaEventIsMappedToConsoleZone() {
        tracking()

        hub.onAreaEvent(14, "정육 코너", "IN")
        hub.onAreaEvent(14, "정육 코너", "OUT")
        flush()

        assertEquals(
            listOf(Triple("za", ZoneEventStatus.ENTER, "14"), Triple("za", ZoneEventStatus.EXIT, "14")),
            delegate.zones,
        )
        assertEquals(2, zoneEvents.size)
        assertEquals(listOf("IN:정육 코너", "OUT:정육 코너"), raw)
    }

    /** 층을 아직 못 잡았으면 콘솔 층으로 귀속한다. */
    @Test fun areaEventWithoutEngineFloorUsesConsoleFloor() {
        configured(consoleFloor = "15")
        provider.start()
        hub.onAreaEvent(14, "정육 코너", "IN")
        flush()

        assertEquals(listOf(Triple("za", ZoneEventStatus.ENTER, "15")), delegate.zones)
    }

    /** 콘솔에 없는 이름은 E3009 — 이벤트를 만들지 않는다. */
    @Test fun unmappedAreaIsE3009() {
        tracking()

        hub.onAreaEvent(14, "수산 코너", "IN")
        flush()

        assertTrue(delegate.zones.isEmpty())
        assertEquals(listOf(SdkErrorCode.ZONE_MAPPING_FAILED), delegate.codes())
    }

    /** DWELL 은 앱 훅까지만 — 서버로 보내지 않는다. */
    @Test fun dwellGoesOnlyToLocalHook() {
        tracking()
        hub.onAreaEvent(14, "정육 코너", "IN")
        flush()

        scheduler.advanceTimeBy(5_001)
        scheduler.runCurrent()

        assertEquals(1, delegate.zones.size)
        assertEquals(1, zoneEvents.filterIsInstance<ZoneEvent.Dwell>().size)
    }

    /**
     * 감사 SP-B2 — 체류 중에 같은 구역 설정이 다시 들어와도(코어의 refreshZones 폴링) DWELL 이 뜬다.
     * 예전엔 apply 마다 판정기를 reset 해 체류 타이머가 지워졌다.
     */
    @Test fun dwellSurvivesReapplyingSameZones() {
        tracking()
        hub.onAreaEvent(14, "정육 코너", "IN")
        flush()

        repeat(2) {
            scheduler.advanceTimeBy(2_000)
            scheduler.runCurrent()
            provider.apply(PositioningConfig(zones = listOf(zoneA.copy())))
        }
        scheduler.advanceTimeBy(1_001)
        scheduler.runCurrent()

        assertEquals(1, zoneEvents.filterIsInstance<ZoneEvent.Dwell>().size)
    }

    /** 측위 중이 아니면 영역 이벤트는 수집·전송하지 않는다(원본 훅에는 남는다). */
    @Test fun areaEventWhileNotRunningIsNotForwarded() {
        configured()
        provider.handleAreaEvent(14, "정육 코너", "IN")
        flush()

        assertTrue(delegate.zones.isEmpty())
        assertEquals(listOf("IN:정육 코너"), raw)
    }

    // MARK: - 일시정지 (PositioningPauseTests 포팅)

    @Test fun startsUnpaused() {
        assertFalse(provider.isPaused)
    }

    /** 측위 중이 아니면 일시정지는 조용히 무시한다 — 켜지면 다음 start 의 좌표가 통째로 버려진다. */
    @Test fun pauseIsIgnoredWhenNotRunning() {
        provider.pause()
        assertFalse(provider.isPaused)
    }

    @Test fun resumeIsIdempotent() {
        provider.resume()
        provider.resume()
        assertFalse(provider.isPaused)
    }

    /** iOS #55 — 내장 provider 의 stop() 은 일시정지를 풀지 않는다(resume·코어의 begin/end 만 푼다). */
    @Test fun stopKeepsPause() {
        tracking()
        provider.pause()
        provider.stop()
        assertTrue(provider.isPaused)
        provider.resume()
        assertFalse(provider.isPaused)
    }

    /** 일시정지는 엔진을 끄지 않는다 — 좌표만 버린다. */
    @Test fun pauseKeepsTheEngineRunningAndDropsPositions() {
        tracking()
        provider.pause()

        hub.onPosition(14, 5.0, 5.0, 1.2)
        flush()

        assertTrue(provider.isPaused)
        assertTrue(delegate.positions.isEmpty())
        assertEquals("일시정지는 엔진을 멈추지 않는다", 0, engine.stops)
        assertTrue("멈춘 동안의 좌표도 수신 진단에는 센다(E4002 오탐 방지)", provider.positioningDiagnostic.hasFix)
    }

    /** ⚠️ 이번 회귀의 본체(iOS 2026-09-10) — 일시정지 중에는 영역 이벤트도 나가지 않는다. */
    @Test fun whilePausedAreaEventsAreNotDelivered() {
        tracking()
        provider.pause()

        hub.onAreaEvent(14, "정육 코너", "IN")
        hub.onAreaEvent(14, "정육 코너", "OUT")
        flush()

        assertTrue("일시정지 중인데 영역 이벤트가 밖으로 나갔다: $raw", raw.isEmpty())
        assertTrue(delegate.zones.isEmpty())
        assertTrue(zoneEvents.isEmpty())
        assertTrue("넘긴 사실은 로그에 남는다", logs.any { it.second.contains("정육 코너") })
    }

    /** 막는 것만 맞으면 절반이다 — 재개하면 다시 나가야 한다. */
    @Test fun afterResumeAreaEventsFlowAgain() {
        tracking()
        provider.pause()
        hub.onAreaEvent(14, "정육 코너", "IN")
        flush()
        provider.resume()
        hub.onAreaEvent(14, "정육 코너", "IN")
        flush()

        assertEquals("재개 뒤의 이벤트가 한 건만 나가야 한다", listOf("IN:정육 코너"), raw)
        assertEquals(1, delegate.zones.size)
    }

    /** 재개는 판정기를 비운다 — 안에서 멈추고 밖에서 재개하면 판정기가 옛 "안" 을 믿는다. */
    @Test fun resumeResetsTheJudge() {
        tracking()
        hub.onAreaEvent(14, "정육 코너", "IN")
        flush()
        provider.pause()
        provider.resume()

        scheduler.advanceTimeBy(10_000)
        scheduler.runCurrent()

        assertTrue("재개 뒤에 옛 진입의 체류가 발화했다", zoneEvents.none { it is ZoneEvent.Dwell })
    }

    // MARK: - 정지·재시작

    /** 정지는 엔진 onStopped 뒤에 끝난다 — 그때 리스너를 푼다. */
    @Test fun stopWaitsForOnStoppedThenReleasesListener() {
        tracking()
        val listener = hub

        provider.stop()
        assertEquals(1, engine.stops)
        assertEquals(Phase.STOPPING, provider.enginePhase)
        assertFalse(provider.isRunning)

        listener.onStopped()
        flush()
        assertEquals(Phase.IDLE, provider.enginePhase)
        assertNull("정지 뒤 리스너를 푼다", engine.current)
        assertNull(provider.detectedFloorId)
        assertTrue("사용자가 끈 것은 오류 코드가 아니다", delegate.reports.isEmpty())
    }

    /** 내려가는 중에 온 start 는 예약만 하고, onStopped 뒤에 이어서 띄운다(iOS 2026-09-10). */
    @Test fun startWhileStoppingIsQueuedUntilStopped() {
        tracking()
        val listener = hub
        provider.stop()

        provider.start()
        assertEquals("아직 띄우면 안 된다", 1, engine.starts)

        listener.onStopped()
        flush()
        assertEquals(2, engine.starts)
        assertTrue(provider.isRunning)
        assertEquals(Phase.STARTING, provider.enginePhase)
        assertEquals("새 가동이라 입장 트리거가 다시 나간다", listOf("b-1", "b-1"), delegate.enters)
    }

    /** onStopped 가 끝내 안 와도 STOPPING 에 고착되지 않는다. 옛 기동의 늦은 콜백은 버린다. */
    @Test fun stopWatchdogUnblocksQueuedStart() {
        tracking()
        val old = hub
        provider.stop()
        provider.start()

        scheduler.advanceTimeBy(UwbPositioningProvider.STOP_TIMEOUT_MS + 1)
        scheduler.runCurrent()

        assertEquals(2, engine.starts)
        assertTrue(provider.isRunning)
        assertTrue(logs.any { it.first == LogLevel.WARN })

        old.onStopped()
        old.onPosition(14, 1.0, 1.0, 1.0)
        flush()
        assertTrue("옛 기동의 늦은 콜백이 새 기동을 건드렸다", provider.isRunning)
        assertEquals(Phase.STARTING, provider.enginePhase)
        assertTrue(delegate.positions.isEmpty())
    }

    /** 엔진이 스스로 멈추면(오류 3·7·10 뒤) 가동이 끝난다 — 자동 재개 없음, WARN 로그. */
    @Test fun selfStopEndsPositioning() {
        tracking()

        hub.onError(3, "bluetooth unavailable")
        hub.onStopped()
        flush()

        assertEquals(Phase.IDLE, provider.enginePhase)
        assertFalse(provider.isRunning)
        assertEquals(listOf(SdkErrorCode.PERMISSION_DENIED), delegate.codes())
        assertEquals("engine=3 bluetooth unavailable", delegate.reports[0].second)
        assertTrue(logs.any { it.first == LogLevel.WARN && it.second == SdkLocalized.t("uwb.stoppedSelf") })
    }

    /**
     * 구동 중 Bluetooth 를 끄면 엔진이 오류 3 + `powered off` 를 주고 멈춘다 — E2004(권한 거부 아님).
     * 앱 훅 onEngineError 는 엔진 원본 번호·문장을 그대로 받는다(iOS 와 같다).
     */
    @Test fun bluetoothTurnedOffWhileTrackingIsE2004() {
        val raw = mutableListOf<Pair<Int, String>>()
        provider.onEngineError = EngineErrorListener { code, message -> raw += code to message }
        tracking()

        hub.onError(3, "bluetooth unavailable: powered off")
        hub.onStopped()
        flush()

        assertEquals(Phase.IDLE, provider.enginePhase)
        assertFalse(provider.isRunning)
        assertEquals(listOf(SdkErrorCode.BLUETOOTH_OFF), delegate.codes())
        assertEquals("engine=3 bluetooth unavailable: powered off", delegate.reports[0].second)
        assertEquals(listOf(3 to "bluetooth unavailable: powered off"), raw)
    }

    // MARK: - 시작 단계 오류

    /** 시작 단계 실패(onStopped 없이 끝남)는 여기서 되돌린다 — 안 그러면 STARTING 에 고착된다. */
    @Test fun startAbortErrorReturnsToIdleWithMappedCode() {
        configured()
        provider.start()

        hub.onError(10, "license is invalid")
        flush()

        assertEquals(Phase.IDLE, provider.enginePhase)
        assertFalse(provider.isRunning)
        assertEquals(listOf(SdkErrorCode.KEY_UNAVAILABLE), delegate.codes()) // 10 = 측위 키 거부(SP-B9)
        assertNull(engine.current)

        // 다시 시작할 수 있다.
        provider.start()
        assertEquals(2, engine.starts)
    }

    /** Bluetooth 를 끈 채 시작하면 시작 단계 실패다 — E2004 로 올리고 IDLE, 켠 뒤 다시 시작할 수 있다. */
    @Test fun bluetoothOffAtStartIsE2004AndIdle() {
        configured()
        provider.start()

        hub.onError(3, "bluetooth unavailable: powered off")
        flush()

        assertEquals(Phase.IDLE, provider.enginePhase)
        assertFalse(provider.isRunning)
        assertEquals(listOf(SdkErrorCode.BLUETOOTH_OFF), delegate.codes())

        provider.start()
        assertEquals(2, engine.starts)
    }

    /** 같은 오류 3 이라도 권한이 없으면 E2003 그대로다 — 설정 앱에서 풀어야 한다. */
    @Test fun bluetoothPermissionAtStartStaysE2003() {
        configured()
        provider.start()

        hub.onError(3, "bluetooth unavailable: permission required — app must request BLUETOOTH_SCAN and wait for user response")
        flush()

        assertEquals(Phase.IDLE, provider.enginePhase)
        assertEquals(listOf(SdkErrorCode.PERMISSION_DENIED), delegate.codes())
    }

    /** 스캔 시작 제한(13)도 시작 단계 실패다 — E3007 로 올리고 IDLE. */
    @Test fun scanThrottleAtStartIsFloorNotDetectedAndIdle() {
        configured()
        provider.start()

        hub.onError(13, "scan started too frequently")
        flush()

        assertEquals(Phase.IDLE, provider.enginePhase)
        assertEquals(listOf(SdkErrorCode.FLOOR_NOT_DETECTED), delegate.codes())
    }

    /** 8(아직 정지 중) — 코드는 올리지 않고, 그 정지가 끝나면 조용히 다시 띄운다. */
    @Test fun stoppingRejectionRetriesAfterOnStopped() {
        configured()
        provider.start()
        val enters = delegate.enters.size

        hub.onError(8, "still stopping")
        flush()
        assertTrue("8 은 호출 순서 문제 — 코드로 올리지 않는다", delegate.reports.isEmpty())
        assertEquals(Phase.STOPPING, provider.enginePhase)
        assertTrue("가동 의사는 유지된다", provider.isRunning)

        hub.onStopped()
        flush()
        assertEquals(2, engine.starts)
        assertEquals(Phase.STARTING, provider.enginePhase)
        assertTrue(provider.isRunning)
        assertEquals("재시도는 입장 트리거를 다시 쏘지 않는다", enters, delegate.enters.size)
    }

    /** 2(이미 시작됨) — 엔진이 이미 돌고 있다. 시작된 것으로 친다. */
    @Test fun alreadyStartedIsTreatedAsStarted() {
        configured()
        provider.start()

        hub.onError(2, "already started")
        flush()

        assertEquals(Phase.SEARCHING, provider.enginePhase)
        assertTrue(provider.isRunning)
        assertTrue(delegate.reports.isEmpty())
    }

    /** 엔진 start 가 동기로 던지면(권한 SecurityException) E2003 · IDLE. */
    @Test fun securityExceptionOnStartIsE2003() {
        engine.throwOnStart = SecurityException("no RANGING")
        configured()

        provider.start()
        flush()

        assertEquals(Phase.IDLE, provider.enginePhase)
        assertFalse(provider.isRunning)
        assertEquals(listOf(SdkErrorCode.PERMISSION_DENIED), delegate.codes())
    }

    // MARK: - 구역 재적재

    /** 재적재 = 엔진 stop → onStopped → 다시 start. 가동·일시정지는 그대로, 입장 트리거는 다시 안 쏜다. */
    @Test fun reloadGeofencesRestartsTheEngineQuietly() {
        tracking()
        provider.pause()
        val first = hub

        provider.reloadGeofences()
        assertEquals(1, engine.stops)
        assertTrue("재적재 중에도 가동 중으로 보여야 한다", provider.isRunning)
        assertTrue(logs.any { it.first == LogLevel.WARN && it.second == SdkLocalized.t("uwb.geofenceReload") })

        first.onStopped()
        flush()

        assertEquals(2, engine.starts)
        assertNotSame("새 기동은 새 리스너(세대)", first, engine.current)
        assertTrue(provider.isRunning)
        assertTrue("일시정지는 재적재에 풀리지 않는다", provider.isPaused)
        assertEquals(Phase.STARTING, provider.enginePhase)
        assertEquals(listOf("b-1"), delegate.enters)
        assertFalse("재적재는 스스로 멈춘 것이 아니다", logs.any { it.second == SdkLocalized.t("uwb.stoppedSelf") })
        assertTrue(delegate.reports.isEmpty())
    }

    /** 측위 중이 아니면 재적재는 할 일이 없다 — 다음 start 가 어차피 새로 읽는다. */
    @Test fun reloadGeofencesIsIgnoredWhenNotRunning() {
        configured()
        provider.reloadGeofences()
        assertEquals(0, engine.stops)
        assertEquals(0, engine.starts)
    }

    /** 재적재 중에 stop 이 오면 stop 이 이긴다 — 다시 띄우지 않는다. */
    @Test fun stopDuringReloadWins() {
        tracking()
        val first = hub
        provider.reloadGeofences()
        provider.stop()

        first.onStopped()
        flush()

        assertEquals(1, engine.starts)
        assertEquals(Phase.IDLE, provider.enginePhase)
        assertFalse(provider.isRunning)
    }

    // MARK: - 존 주입

    /** 빈 존 목록도 그대로 반영한다 — 지운 구역에서 시책이 계속 발화하면 안 된다. */
    @Test fun emptyZoneListIsApplied() {
        tracking()
        provider.apply(PositioningConfig(zones = emptyList()))

        hub.onAreaEvent(14, "정육 코너", "IN")
        flush()

        assertTrue(delegate.zones.isEmpty())
        assertEquals(listOf(SdkErrorCode.ZONE_MAPPING_FAILED), delegate.codes())
    }

    /** 구역만 바꾸는 갱신(applyZones — 코어의 refreshZones)은 등록 로케이터 수를 지우지 않는다. */
    @Test fun anchorsAreKeptWhenOnlyZonesChange() {
        configured()
        provider.applyZones(listOf(zoneA))

        assertEquals(2, provider.positioningDiagnostic.registeredCount)
    }

    /**
     * 감사 SP-C9 — apply(config) 는 그 층의 전부다: 빈 앵커는 「등록 없음」(층 해제 포함). 예전엔 「안 바뀜」으로 보고
     * 무시해, setFloorMap(null) 뒤에도 옛 등록 수(2)가 남아 수신 점검 문맥이 틀렸다.
     */
    @Test fun clearingTheFloorClearsRegisteredAnchors() {
        configured()
        provider.apply(PositioningConfig())

        assertEquals(0, provider.positioningDiagnostic.registeredCount)
    }

    // MARK: - Fix round 1

    /**
     * 시작 중(STARTING)에 온 onStopped 는 앞 기동의 늦은 통지다 — 엔진은 시작 단계 실패를 onError 로만
     * 알리고 onStopped 는 우리가 stop 한 뒤에만 보낸다. 받아들이면 방금 띄운 측위를 스스로 멈춘 것으로 오인한다.
     */
    @Test fun staleOnStoppedWhileStartingIsIgnored() {
        configured()
        provider.start()
        assertEquals(Phase.STARTING, provider.enginePhase)

        hub.onStopped()
        flush()

        assertEquals(Phase.STARTING, provider.enginePhase)
        assertTrue(provider.isRunning)
        assertNotNull("리스너가 풀리면 안 된다", engine.current)
        assertFalse(logs.any { it.second == SdkLocalized.t("uwb.stoppedSelf") })

        hub.onStarted()
        flush()
        assertEquals(Phase.SEARCHING, provider.enginePhase)
    }

    /** 층 지정 해제(빈 층) 뒤에는 옛 층으로 E3008 을 대조하지 않고, 이벤트를 옛 층으로 귀속하지 않는다. */
    @Test fun clearedConsoleFloorStopsMismatchAndAttribution() {
        configured(consoleFloor = "15")
        provider.apply("", "") // setFloorMap(null) 이 코어를 통해 부르는 것
        provider.apply(PositioningConfig(zones = listOf(zoneA))) // 매핑 확인용으로 존만 다시

        provider.start()
        hub.onAreaEvent(14, "정육 코너", "IN") // 엔진 층 미탐지 상태
        hub.onStarted()
        hub.onTrackingStarted(14)
        flush()

        assertFalse("해제한 층으로 대조했다", delegate.codes().contains(SdkErrorCode.FLOOR_ID_MISMATCH))
        assertEquals(listOf(Triple("za", ZoneEventStatus.ENTER, null as String?)), delegate.zones)
    }

    /** 층을 다시 지정하면 새 층 기준으로 다시 대조한다(같은 엔진 층이어도 한 번 더 알린다). */
    @Test fun changingConsoleFloorRearmsMismatchWarning() {
        configured(consoleFloor = "15")
        provider.start()
        hub.onStarted()
        hub.onTrackingStarted(14)
        flush()
        provider.apply("b-1", "16")
        hub.onTrackingStopped(14)
        hub.onTrackingStarted(14)
        flush()

        assertEquals(
            listOf("engine=14 console=15", "engine=14 console=16"),
            delegate.reports.filter { it.first == SdkErrorCode.FLOOR_ID_MISMATCH }.map { it.second },
        )
    }
}
