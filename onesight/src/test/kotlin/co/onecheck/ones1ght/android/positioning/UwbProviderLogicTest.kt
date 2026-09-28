package co.onecheck.ones1ght.android.positioning

import co.onecheck.ones1ght.android.model.Coordinates
import co.onecheck.ones1ght.android.model.Position
import co.onecheck.ones1ght.android.model.Zone
import co.onecheck.ones1ght.android.model.ZoneEvent
import co.onecheck.ones1ght.android.model.ZoneEventStatus
import co.onecheck.ones1ght.android.runtime.LogLevel
import co.onecheck.ones1ght.android.runtime.SdkErrorCode
import co.onecheck.ones1ght.android.runtime.SdkLocalized
import co.onecheck.ones1ght.android.zone.CoroutineDwellScheduler
import co.onecheck.ones1ght.android.zone.ZoneEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestDispatcher
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * UWB 측위 어댑터의 안드로이드 비의존 부분 — 앵커 진단·주소 추출·세션 흐름 오케스트레이션.
 *
 * 실제 레인징(android.ranging + 좌표 엔진)은 [RangingEngine] 뒤에 있고 여기서는 가짜
 * ([FakeEngine])로 갈아 끼운다. 엔진 콜백은 아무 스레드에서나 오므로 provider 는 메인
 * 디스패처로 넘긴다 — 테스트는 [StandardTestDispatcher] 를 메인으로 주고 `runCurrent()` 로 흘린다.
 *
 * 포팅 원본: b804f3b UwbPositioningProvider.swift(세션 흐름·진단·좌표 전달),
 * 현재 UwbPositioningProvider.swift(pause·phase), PositioningPauseTests.swift(일시정지 중 영역 이벤트 차단).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UwbProviderLogicTest {

    // MARK: - AnchorTracker

    @Test fun anchorTrackerReportsMissingAnchor() {
        val t = AnchorTracker()
        t.register(setOf(0x0B4A, 0x0B4B, 0x0B4C))
        t.seen(0x0B4A)
        t.seen(0x0B4C)

        val d = t.diagnostic(hasFix = false)

        assertEquals(3, d.registeredCount)
        assertEquals(2, d.receivedCount)
        assertEquals(2, d.matchedCount)
        assertEquals(listOf(0x0B4B), d.missingAddresses)
        assertEquals("0x0B4B", d.missingLabel)
        assertFalse(d.hasFix)
        assertTrue("안드로이드는 앵커별 수신을 안다", d.canAttributePerAnchor)
    }

    @Test fun anchorTrackerCountsUnregisteredAsReceivedButNotMatched() {
        val t = AnchorTracker()
        t.register(setOf(0x0001, 0x0002))
        t.seen(0x0001)
        t.seen(0x0001)
        t.seen(0x0999)

        val d = t.diagnostic(hasFix = true)

        assertEquals(2, d.registeredCount)
        assertEquals(2, d.receivedCount)
        assertEquals(1, d.matchedCount)
        assertEquals(listOf(0x0002), d.missingAddresses)
        assertTrue(d.hasFix)
    }

    @Test fun anchorTrackerClearSeenKeepsRegistration() {
        val t = AnchorTracker()
        t.register(setOf(0x0001))
        t.seen(0x0001)
        t.clearSeen()

        val d = t.diagnostic(hasFix = false)
        assertEquals(1, d.registeredCount)
        assertEquals(0, d.receivedCount)
        assertEquals(listOf(0x0001), d.missingAddresses)
    }

    // MARK: - 주소 하위 2바이트

    @Test fun shortAddressOfTwoByteAddress() {
        assertEquals(0x0B4B, AnchorTracker.shortAddress(byteArrayOf(0x0B, 0x4B)))
    }

    @Test fun shortAddressOfExtendedAddressIsLastTwoBytes() {
        val ext = byteArrayOf(0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x0B, 0x4B)
        assertEquals(0x0B4B, AnchorTracker.shortAddress(ext))
    }

    @Test fun shortAddressIsUnsigned() {
        assertEquals(0xABCD, AnchorTracker.shortAddress(byteArrayOf(0xAB.toByte(), 0xCD.toByte())))
    }

    @Test fun shortAddressOfTooShortInputIsNull() {
        assertNull(AnchorTracker.shortAddress(byteArrayOf(0x01)))
        assertNull(AnchorTracker.shortAddress(null))
    }

    // MARK: - 세션 흐름 (가짜 엔진)

    private class FakeEngine : RangingEngine {
        val opens = mutableListOf<Int>()
        var closes = 0
        val appliedAnchors = mutableListOf<Map<Int, DoubleArray>>()
        var listener: RangingEngine.Listener? = null
        var throwOnOpen: RuntimeException? = null

        override fun open(sessionId: Int, listener: RangingEngine.Listener) {
            throwOnOpen?.let { throw it }
            opens += sessionId
            this.listener = listener
        }

        override fun close() {
            closes += 1
        }

        override fun applyAnchors(anchors: Map<Int, DoubleArray>) {
            appliedAnchors += anchors
        }
    }

    private class RecordingDelegate : PositioningProviderDelegate {
        val positions = mutableListOf<Triple<Coordinates, String?, Long>>()
        val zones = mutableListOf<Pair<String, ZoneEventStatus>>()
        val enters = mutableListOf<String>()
        val reports = mutableListOf<SdkErrorCode>()

        override fun onPosition(provider: PositioningProvider, coordinates: Coordinates, floorId: String?, atMs: Long) {
            positions += Triple(coordinates, floorId, atMs)
        }

        override fun onZone(provider: PositioningProvider, zoneId: String, status: ZoneEventStatus, floorId: String?, atMs: Long) {
            zones += zoneId to status
        }

        override fun onEnter(provider: PositioningProvider, buildingId: String) {
            enters += buildingId
        }

        override fun onReport(provider: PositioningProvider, code: SdkErrorCode, context: String) {
            reports += code
        }
    }

    private val sq = listOf(Position(0.0, 0.0), Position(10.0, 0.0), Position(10.0, 10.0), Position(0.0, 10.0))
    private val zoneA = Zone("za", "A", sq, dwellSeconds = 5)

    private lateinit var scheduler: TestCoroutineScheduler
    private lateinit var main: TestDispatcher
    private lateinit var scope: CoroutineScope
    private lateinit var engine: FakeEngine
    private lateinit var zoneEngine: ZoneEngine
    private lateinit var delegate: RecordingDelegate
    private lateinit var provider: UwbPositioningProvider
    private val zoneEvents = mutableListOf<ZoneEvent>()
    private val logs = mutableListOf<Pair<LogLevel, String>>()

    @Before fun setUp() {
        SdkLocalized.language = "ko"
        scheduler = TestCoroutineScheduler()
        main = StandardTestDispatcher(scheduler)
        scope = CoroutineScope(SupervisorJob() + main)
        engine = FakeEngine()
        zoneEngine = ZoneEngine(CoroutineDwellScheduler(scope))
        delegate = RecordingDelegate()
        provider = UwbPositioningProvider.create(engine, zoneEngine, main, clock = { scheduler.currentTime })
        provider.delegate = delegate
        provider.onZoneEvent = { zoneEvents += it }
        provider.onLog = { level, msg -> logs += level to msg }
    }

    @After fun tearDown() {
        provider.stop()
        scope.cancel()
        SdkLocalized.language = null
    }

    private fun flush() = scheduler.runCurrent()

    private fun configured(sessionId: Int? = 7) {
        provider.apply("b-1", "f-1")
        provider.apply(
            PositioningConfig(
                anchors = mapOf(0x0001 to doubleArrayOf(0.0, 0.0, 2.0), 0x0002 to doubleArrayOf(5.0, 0.0, 2.0)),
                sessionId = sessionId,
                zones = listOf(zoneA),
            ),
        )
    }

    private fun startOpened() {
        configured()
        provider.start()
        engine.listener!!.onOpened()
        flush()
    }

    /** 엔진 스레드에서 좌표 한 점이 오고, 메인에서 처리되기까지. */
    private fun fix(x: Double, y: Double, atMs: Long) {
        scheduler.advanceTimeBy(atMs - scheduler.currentTime)
        engine.listener!!.onPosition(x, y, 1.2)
        flush()
    }

    @Test fun startWithoutSessionIdIsRefusedWithE3003() {
        configured(sessionId = null)

        provider.start()
        flush()

        assertTrue(engine.opens.isEmpty())
        assertEquals(listOf(SdkErrorCode.SESSION_ID_MISSING), delegate.reports)
        assertEquals(Phase.IDLE, provider.phase)
        assertFalse(provider.isRunning)
    }

    @Test fun startOpensSessionWithInjectedSessionIdAndEntersBuilding() {
        configured()

        provider.start()

        assertEquals(listOf(7), engine.opens)
        assertEquals(Phase.STARTING, provider.phase)
        assertEquals(listOf("b-1"), delegate.enters)

        engine.listener!!.onOpened()
        flush()
        assertEquals(Phase.SEARCHING, provider.phase)
    }

    @Test fun applyConfigInjectsAnchorsIntoEngineAndTrackerAndZonesIntoJudge() {
        configured()

        assertEquals(1, engine.appliedAnchors.size)
        assertEquals(setOf(0x0001, 0x0002), engine.appliedAnchors[0].keys)
        assertEquals(2, provider.positioningDiagnostic!!.registeredCount)
        assertEquals(listOf(zoneA), zoneEngine.zones)
    }

    @Test fun positionIsForwardedOnMainAndFeedsZoneJudge() {
        startOpened()

        engine.listener!!.onPosition(5.0, 5.0, 1.2)
        assertTrue("메인으로 넘기기 전에는 아무것도 나가지 않는다", delegate.positions.isEmpty())
        flush()

        assertEquals(1, delegate.positions.size)
        assertEquals(Coordinates(5.0, 5.0, 1.2), delegate.positions[0].first)
        assertEquals("f-1", delegate.positions[0].second)
        assertEquals(Phase.TRACKING, provider.phase)
        assertTrue(provider.positioningDiagnostic!!.hasFix)

        fix(5.0, 5.0, 1_000)
        fix(5.0, 5.0, 2_000)

        assertEquals(listOf("za" to ZoneEventStatus.ENTER), delegate.zones)
        assertEquals(1, zoneEvents.size)
        assertTrue(zoneEvents[0] is ZoneEvent.Enter)
    }

    @Test fun anchorSeenFeedsDiagnostic() {
        startOpened()

        engine.listener!!.onAnchorSeen(0x0001)
        flush()

        val d = provider.positioningDiagnostic!!
        assertEquals(1, d.receivedCount)
        assertEquals(listOf(0x0002), d.missingAddresses)
    }

    @Test fun dwellGoesOnlyToOnZoneEvent() {
        startOpened()
        zoneEngine.onEvent!!.invoke(ZoneEvent.Dwell(zoneA, 5.0, 0))

        assertTrue(delegate.zones.isEmpty())
        assertEquals(1, zoneEvents.size)
    }

    @Test fun pausedPositionsAreNotConsumed() {
        startOpened()
        provider.pause()

        fix(5.0, 5.0, 0)

        assertTrue(provider.isPaused)
        assertTrue(delegate.positions.isEmpty())
        assertEquals(1, engine.opens.size)
        assertEquals("일시정지는 세션을 닫지 않는다", 0, engine.closes)
    }

    /** PositioningPauseTests: 일시정지 중에는 영역 이벤트를 내보내지 않는다. */
    @Test fun whilePausedZoneEventsAreNotDelivered() {
        startOpened()
        provider.pause()

        zoneEngine.onEvent!!.invoke(ZoneEvent.Enter(zoneA, 0))
        zoneEngine.onEvent!!.invoke(ZoneEvent.Exit(zoneA, 1_000))
        zoneEngine.onEvent!!.invoke(ZoneEvent.Dwell(zoneA, 5.0, 2_000))

        assertTrue("delegate.onZone 이 나갔다: ${delegate.zones}", delegate.zones.isEmpty())
        assertTrue("onZoneEvent 가 나갔다: $zoneEvents", zoneEvents.isEmpty())
    }

    /** PositioningPauseTests: 재개하면 영역 이벤트가 다시 나간다(영원히 막는 구현 방지). */
    @Test fun afterResumeZoneEventsFlowAgain() {
        startOpened()
        provider.pause()
        zoneEngine.onEvent!!.invoke(ZoneEvent.Enter(zoneA, 0))
        provider.resume()
        zoneEngine.onEvent!!.invoke(ZoneEvent.Enter(zoneA, 1_000))

        assertEquals(listOf("za" to ZoneEventStatus.ENTER), delegate.zones)
        assertEquals(1, zoneEvents.size)
    }

    @Test fun resumeResetsZoneJudge() {
        startOpened()
        fix(5.0, 5.0, 0)
        fix(5.0, 5.0, 1_000)
        fix(5.0, 5.0, 2_000)
        assertEquals("za", zoneEngine.activeZoneId)

        provider.pause()
        provider.resume()

        assertNull(zoneEngine.activeZoneId)
    }

    @Test fun sessionIdChangeWhileRunningReopensSession() {
        startOpened()

        provider.apply(PositioningConfig(sessionId = 9))

        assertEquals(1, engine.closes)
        assertEquals(listOf(7), engine.opens)
        engine.listener!!.onClosed(RangingErrorMapping.REASON_LOCAL_REQUEST)
        flush()

        assertEquals(listOf(7, 9), engine.opens)
        assertTrue(provider.isRunning)
        assertTrue("정상 종료는 코드를 올리지 않는다", delegate.reports.isEmpty())
        assertEquals("재오픈은 입장 트리거를 다시 쏘지 않는다", listOf("b-1"), delegate.enters)
    }

    @Test fun sameSessionIdOrNotRunningDoesNotReopen() {
        startOpened()
        provider.apply(PositioningConfig(sessionId = 7))
        assertEquals(0, engine.closes)

        provider.stop()
        provider.apply(PositioningConfig(sessionId = 11))
        assertEquals(1, engine.closes)
        assertEquals(listOf(7), engine.opens)
    }

    @Test fun openFailedReportsMappedCodeAndReturnsToIdle() {
        configured()
        provider.start()

        engine.listener!!.onOpenFailed(RangingErrorMapping.REASON_UNSUPPORTED)
        flush()

        assertEquals(listOf(SdkErrorCode.DEVICE_NOT_SUPPORTED), delegate.reports)
        assertEquals(Phase.IDLE, provider.phase)
        assertFalse(provider.isRunning)
    }

    @Test fun unexpectedCloseReportsMappedCodeAndStops() {
        startOpened()

        engine.listener!!.onClosed(RangingErrorMapping.REASON_NO_PEERS_FOUND)
        flush()

        assertEquals(listOf(SdkErrorCode.NO_POSITION_FIX), delegate.reports)
        assertEquals(Phase.IDLE, provider.phase)
        assertFalse(provider.isRunning)
    }

    @Test fun localCloseAfterStopReportsNothing() {
        startOpened()
        provider.stop()
        assertEquals(Phase.STOPPING, provider.phase)

        engine.listener!!.onClosed(RangingErrorMapping.REASON_LOCAL_REQUEST)
        flush()

        assertTrue(delegate.reports.isEmpty())
        assertEquals(Phase.IDLE, provider.phase)
    }

    @Test fun securityExceptionOnOpenIsE2003AndStaysIdle() {
        configured()
        engine.throwOnOpen = SecurityException("RANGING not granted")

        provider.start()
        flush()

        assertEquals(listOf(SdkErrorCode.PERMISSION_DENIED), delegate.reports)
        assertEquals(Phase.IDLE, provider.phase)
        assertFalse(provider.isRunning)
    }

    @Test fun unsupportedOnOpenIsE2002() {
        configured()
        engine.throwOnOpen = UnsupportedOperationException("API 34 < 37")

        provider.start()
        flush()

        assertEquals(listOf(SdkErrorCode.DEVICE_NOT_SUPPORTED), delegate.reports)
        assertEquals(Phase.IDLE, provider.phase)
        assertFalse(provider.isRunning)
    }

    @Test fun startWhileStoppingIsQueuedUntilClosed() {
        startOpened()
        provider.stop()

        provider.start()
        assertEquals(listOf(7), engine.opens)

        engine.listener!!.onClosed(RangingErrorMapping.REASON_LOCAL_REQUEST)
        flush()

        assertEquals(listOf(7, 7), engine.opens)
        assertTrue(provider.isRunning)
    }

    @Test fun positionsFromAClosedSessionAreDropped() {
        startOpened()
        val old = engine.listener!!
        provider.stop()
        old.onClosed(RangingErrorMapping.REASON_LOCAL_REQUEST)
        flush()
        provider.start()
        engine.listener!!.onOpened()
        flush()

        old.onPosition(5.0, 5.0, 1.0)
        flush()

        assertTrue(delegate.positions.isEmpty())
    }

    @Test fun diagnosticLogIsWrittenOnceFiveSecondsAfterStart() {
        startOpened()
        val diag = { logs.count { it.second.startsWith("측위 엔진 ") && it.second.contains("콘솔 로케이터") } }

        scheduler.advanceTimeBy(4_999)
        scheduler.runCurrent()
        assertEquals(0, diag())

        scheduler.advanceTimeBy(1)
        scheduler.runCurrent()
        assertEquals(1, diag())
        assertEquals("좌표 없고 유효 앵커 3대 미만 → ERROR", LogLevel.ERROR, logs.last { it.second.contains("콘솔 로케이터") }.first)

        scheduler.advanceTimeBy(60_000)
        scheduler.runCurrent()
        assertEquals(1, diag())
    }

    @Test fun stopClearsPauseAndFix() {
        startOpened()
        fix(1.0, 1.0, 0)
        provider.pause()

        provider.stop()

        assertFalse(provider.isPaused)
        assertFalse(provider.positioningDiagnostic!!.hasFix)
    }

    @Test fun anchorsAreKeptWhenConfigHasNone() {
        configured()
        provider.apply(PositioningConfig(zones = emptyList()))

        assertEquals(1, engine.appliedAnchors.size)
        assertEquals(2, provider.positioningDiagnostic!!.registeredCount)
        assertTrue("빈 구역 목록도 그대로 반영한다", zoneEngine.zones.isEmpty())
    }

    @Test fun anchorMapIsPassedThrough() {
        configured()
        assertArrayEquals(doubleArrayOf(5.0, 0.0, 2.0), engine.appliedAnchors[0][0x0002], 0.0)
    }

    // MARK: - Fix round 1

    /** 일시정지 중 세션 번호가 바뀌어도 일시정지·가동 상태가 유지된다(조용한 재개 금지). */
    @Test fun sessionIdChangeWhilePausedKeepsPauseAndRunning() {
        startOpened()
        provider.pause()

        provider.apply(PositioningConfig(sessionId = 9))
        assertTrue(provider.isRunning)
        assertTrue(provider.isPaused)

        engine.listener!!.onClosed(RangingErrorMapping.REASON_LOCAL_REQUEST)
        flush()
        assertEquals(listOf(7, 9), engine.opens)
        assertTrue(provider.isRunning)
        assertTrue(provider.isPaused)

        engine.listener!!.onOpened()
        flush()
        fix(5.0, 5.0, 0)
        zoneEngine.onEvent!!.invoke(ZoneEvent.Enter(zoneA, 0))
        assertTrue(delegate.positions.isEmpty())
        assertTrue(delegate.zones.isEmpty())
        assertTrue(zoneEvents.isEmpty())

        provider.resume()
        assertFalse(provider.isPaused)
        fix(5.0, 5.0, 1_000)
        assertEquals(1, delegate.positions.size)
    }

    @Test fun sessionIdChangeKeepsRunningDuringReopenWindow() {
        startOpened()
        provider.apply(PositioningConfig(sessionId = 9))
        assertTrue("재오픈 중에도 가동 중으로 보여야 한다", provider.isRunning)
    }

    /** onClosed 가 끝내 안 와도 STOPPING 에 고착되지 않는다. */
    @Test fun stopWatchdogUnblocksQueuedStart() {
        startOpened()
        val old = engine.listener!!
        provider.stop()
        provider.start()
        assertEquals(listOf(7), engine.opens)

        scheduler.advanceTimeBy(UwbPositioningProvider.STOP_TIMEOUT_MS + 1)
        scheduler.runCurrent()

        assertEquals(listOf(7, 7), engine.opens)
        assertTrue(provider.isRunning)
        assertTrue(logs.any { it.first == LogLevel.WARN })

        // 옛 세대의 늦은 onClosed 는 새 세션을 건드리지 않는다.
        old.onClosed(RangingErrorMapping.REASON_LOCAL_REQUEST)
        flush()
        assertTrue(provider.isRunning)
        assertEquals(Phase.STARTING, provider.phase)
    }

    @Test fun stopWatchdogIsCancelledByTimelyClose() {
        startOpened()
        provider.stop()
        engine.listener!!.onClosed(RangingErrorMapping.REASON_LOCAL_REQUEST)
        flush()
        provider.start()
        val opens = engine.opens.size

        scheduler.advanceTimeBy(UwbPositioningProvider.STOP_TIMEOUT_MS + 1)
        scheduler.runCurrent()

        assertEquals(opens, engine.opens.size)
        assertTrue(provider.isRunning)
    }

    /** 사용자가 끈 것은 어떤 사유로 닫혀도 오류 코드가 아니다. */
    @Test fun requestedStopNeverReportsErrorCode() {
        startOpened()
        provider.stop()
        engine.listener!!.onClosed(RangingErrorMapping.REASON_UNKNOWN)
        flush()
        assertTrue(delegate.reports.isEmpty())
        assertEquals(Phase.IDLE, provider.phase)
    }

    @Test fun openFailedAfterStopDuringStartingReportsNothing() {
        configured()
        provider.start()
        provider.stop()
        engine.listener!!.onOpenFailed(RangingErrorMapping.REASON_UNSUPPORTED)
        flush()
        assertTrue(delegate.reports.isEmpty())
        assertEquals(Phase.IDLE, provider.phase)
    }

    /** 수신 점검의 hasFix 는 일시정지로 지워지지 않는다. */
    @Test fun pauseDoesNotClearDiagnosticFix() {
        startOpened()
        fix(1.0, 1.0, 0)
        provider.pause()
        assertTrue(provider.positioningDiagnostic!!.hasFix)
    }

    @Test fun fixWhilePausedCountsForDiagnostic() {
        startOpened()
        provider.pause()
        fix(1.0, 1.0, 0)
        assertTrue(provider.positioningDiagnostic!!.hasFix)
        assertTrue(delegate.positions.isEmpty())
    }
}
