package co.onecheck.ones1ght.android.positioning

import android.content.ContextWrapper
import co.onecheck.ones1ght.android.DebugLogListener
import co.onecheck.ones1ght.android.model.Coordinates
import co.onecheck.ones1ght.android.runtime.LogLevel
import co.onecheck.ones1ght.android.runtime.SdkLocalized
import co.onecheck.ones1ght.android.positioning.UwbPositioningProvider.PositioningPhase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 0.0.5 에 연 공개 표면 — iOS 0.1.24 UwbPositioningProvider 와 같은 관찰 상태·훅·진단·엔진 기동 분리.
 * 내부 오케스트레이션은 [UwbProviderLogicTest] 가 본다. 여기서는 "밖에서 보이는 값이 iOS 와 같은 때 바뀌는가".
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UwbProviderPublicSurfaceTest {

    private lateinit var scheduler: TestCoroutineScheduler
    private lateinit var engine: FakeHubEngine
    private lateinit var provider: UwbPositioningProvider
    private var changes = 0

    @Before fun setUp() {
        SdkLocalized.language = "en"
        scheduler = TestCoroutineScheduler()
        engine = FakeHubEngine()
        provider = UwbPositioningProvider.create(engine, StandardTestDispatcher(scheduler), clock = { scheduler.currentTime })
        provider.license = "lic-0123456789"
        provider.onChange = ProviderChangeListener { changes += 1 }
    }

    @After fun tearDown() {
        provider.stopDetection()
        SdkLocalized.language = null
    }

    private fun flush() = scheduler.runCurrent()

    private val hub: HubEngine.Listener get() = engine.current!!

    @Test fun phaseAndRunningFlowFollowTheEngine() {
        assertEquals(PositioningPhase.IDLE, provider.phase)
        assertFalse(provider.isDetecting)
        provider.start()
        assertEquals(PositioningPhase.STARTING, provider.phaseFlow.value)
        assertTrue(provider.isRunningFlow.value)
        assertTrue(provider.isDetecting)
        hub.onStarted(); flush()
        assertEquals(PositioningPhase.SEARCHING, provider.phase)
        hub.onTrackingStarted(14); flush()
        assertEquals(PositioningPhase.TRACKING, provider.phase)
        assertEquals(14L, provider.detectedFloorIdFlow.value)
        provider.stop()
        assertEquals(PositioningPhase.STOPPING, provider.phase)
        assertFalse(provider.isRunning)
        assertFalse(provider.isDetecting)
        hub.onStopped(); flush()
        assertEquals(PositioningPhase.IDLE, provider.phase)
        assertNull(provider.detectedFloorId)
        assertTrue("상태가 바뀔 때마다 Java 통지가 와야 한다", changes > 0)
    }

    @Test fun latestPositionAndCountLikeIos() {
        provider.start()
        hub.onStarted(); hub.onTrackingStarted(14); hub.onPosition(14, 1.0, 2.0, 0.5); flush()
        assertEquals(Coordinates(1.0, 2.0, 0.5), provider.latestPosition)
        assertEquals(Coordinates(1.0, 2.0, 0.5), provider.latestPositionFlow.value)
        assertEquals(1, provider.measurementCountFlow.value)

        // 일시정지 — 마지막 점을 살아 있는 것처럼 두지 않는다. 엔진이 주는 좌표는 세지 않는다.
        provider.pause()
        assertTrue(provider.isPausedFlow.value)
        assertNull(provider.latestPosition)
        hub.onPosition(14, 3.0, 3.0, 0.0); flush()
        assertNull(provider.latestPosition)
        assertEquals(1, provider.measurementCount)
        provider.resume()
        assertFalse(provider.isPaused)
        hub.onPosition(14, 4.0, 4.0, 0.0); flush()
        assertEquals(Coordinates(4.0, 4.0, 0.0), provider.latestPosition)
        assertEquals(2, provider.measurementCount)

        // 층을 놓치면 좌표도 지운다.
        hub.onTrackingStopped(14); flush()
        assertNull(provider.latestPosition)

        // 다시 시작하면 셈이 0 부터.
        provider.stop(); hub.onStopped(); flush()
        provider.start()
        assertEquals(0, provider.measurementCount)
    }

    @Test fun hooksDeliverRawEngineValues() {
        val floors = mutableListOf<Long?>()
        val errors = mutableListOf<Pair<Int, String>>()
        val raw = mutableListOf<String>()
        provider.onFloorDetected = FloorDetectedListener { floors += it }
        provider.onEngineError = EngineErrorListener { code, message -> errors += code to message }
        provider.onRawAreaEvent = RawAreaEventListener { floorId, name, inOut, _ -> raw += "$floorId:$inOut:$name" }
        provider.start()
        hub.onStarted(); hub.onTrackingStarted(14); hub.onAreaEvent(14, "Meat", "IN"); hub.onError(5, "session")
        hub.onTrackingStopped(14); flush()
        assertEquals(listOf(14L, null), floors)
        assertEquals(listOf(5 to "session"), errors)
        assertEquals(listOf("14:IN:Meat"), raw)
    }

    @Test fun missingLicenseIsReportedAsEngineError1() {
        val errors = mutableListOf<Int>()
        provider.onEngineError = EngineErrorListener { code, _ -> errors += code }
        provider.license = ""
        provider.start()
        assertEquals(listOf(1), errors)
        assertEquals(PositioningPhase.IDLE, provider.phase)
        assertFalse(provider.isRunning)
    }

    @Test fun noteJoinsTheLogStream() {
        val seen = mutableListOf<Pair<LogLevel, String>>()
        provider.onLog = DebugLogListener { level, msg -> seen += level to msg }
        provider.note("hello")
        provider.note(LogLevel.WARN, "careful")
        assertEquals(listOf(LogLevel.LOG to "hello", LogLevel.WARN to "careful"), seen)
        assertEquals(listOf("hello", "careful"), provider.log.takeLast(2))
        repeat(250) { provider.note("line $it") }
        assertEquals(200, provider.logFlow.value.size)
        assertEquals("line 249", provider.log.last())
    }

    @Test fun diagnosticNeverFakesPerAnchorData() {
        provider.apply(PositioningConfig(anchors = mapOf(0x0002 to doubleArrayOf(1.0, 0.0, 2.0), 0x0001 to doubleArrayOf(0.0, 0.0, 2.0))))
        provider.start()
        var d = provider.diagnostic
        assertEquals(listOf(1, 2), d.registered)
        assertEquals(emptyList<Int>(), d.received)
        assertEquals(emptyList<Int>(), d.missing)
        assertFalse(d.hasFix)
        assertFalse(d.canPosition)
        hub.onStarted(); hub.onTrackingStarted(14); hub.onPosition(14, 1.0, 1.0, 0.0); flush()
        d = provider.diagnostic
        assertEquals(listOf(1, 2), d.received)
        assertEquals(listOf(1, 2), d.matched)
        assertEquals(emptyList<Int>(), d.missing)
        assertTrue(d.canPosition)
        assertTrue(d.summary, d.summary.contains("tracking") && d.summary.contains("14"))
        assertFalse(provider.positioningDiagnostic.canAttributePerAnchor)
    }

    /** iOS startDetection — 엔진만 띄워 층부터 찾는다. 좌표는 측위를 켜기 전까지 버린다. */
    @Test fun detectionRunsWithoutPositioning() {
        val floors = mutableListOf<Long?>()
        provider.onFloorDetected = FloorDetectedListener { floors += it }
        provider.startDetection()
        assertEquals(1, engine.starts)
        assertEquals(PositioningPhase.STARTING, provider.phase)
        assertFalse(provider.isRunning)
        hub.onStarted(); hub.onTrackingStarted(14); hub.onPosition(14, 1.0, 1.0, 0.0); flush()
        assertEquals(listOf<Long?>(14L), floors)
        assertNull("측위 전 좌표는 버린다", provider.latestPosition)
        assertTrue(provider.isDetecting)

        // stop() 은 측위만 끈다 — 엔진만 도는 상태에서는 아무것도 안 한다(iOS 와 같다).
        provider.stop()
        assertEquals(0, engine.stops)
        assertEquals(PositioningPhase.TRACKING, provider.phase)

        // 측위를 켜면 엔진을 다시 띄우지 않고 이어서 쓴다.
        provider.start()
        assertEquals(1, engine.starts)
        assertTrue(provider.isRunning)
        hub.onPosition(14, 2.0, 2.0, 0.0); flush()
        assertEquals(Coordinates(2.0, 2.0, 0.0), provider.latestPosition)

        provider.stopDetection()
        assertEquals(1, engine.stops)
        assertFalse(provider.isRunning)
        assertEquals(PositioningPhase.STOPPING, provider.phase)
    }

    /** JVM 단위테스트의 SDK_INT 는 0 — API 37 미만이라 엔진 클래스를 건드리지 않고 false. */
    @Test fun isSupportedIsFalseBelowApi37() {
        assertFalse(UwbPositioningProvider.isSupported(ContextWrapper(null)))
    }

    /** API 37 미만에서 만든 provider 는 시작하면 미지원 기기(E2002)로 끝난다 — 던지지 않는다. */
    @Test fun unavailableEngineEndsAsUnsupported() {
        val p = UwbPositioningProvider.create(UnavailableHubEngine(), StandardTestDispatcher(scheduler), clock = { 0L })
        val errors = mutableListOf<Int>()
        p.onEngineError = EngineErrorListener { code, _ -> errors += code }
        p.license = "lic"
        p.start()
        flush()
        assertEquals(listOf(12), errors)
        assertEquals(PositioningPhase.IDLE, p.phase)
        assertFalse(p.isRunning)
    }
}
