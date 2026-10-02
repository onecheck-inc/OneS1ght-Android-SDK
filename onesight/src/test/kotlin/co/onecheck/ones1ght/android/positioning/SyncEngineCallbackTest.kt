package co.onecheck.ones1ght.android.positioning

import co.onecheck.ones1ght.android.model.Coordinates
import co.onecheck.ones1ght.android.model.ZoneEventStatus
import co.onecheck.ones1ght.android.runtime.SdkErrorCode
import co.onecheck.ones1ght.android.runtime.SdkLocalized
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 감사 SP-C10 — 가짜 엔진의 **동기 콜백 모드**(FakeHubEngine.onStartSync)로, 엔진이 start() 안에서 곧바로 콜백을 주고
 * 운영 디스패처(Main.immediate 자리 = Unconfined)가 그 자리에서 이어 돌리는 경로를 밟는다. 예전 가짜 엔진은 콜백이
 * 늘 비동기라 SP-B3(시작 안에서 접힌 엔진 → 측위 중으로 고착) 같은 결함을 못 잡았다.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SyncEngineCallbackTest {

    private class Recorder : PositioningProviderDelegate {
        val stops = mutableListOf<Boolean>()
        val codes = mutableListOf<SdkErrorCode>()
        override fun onPosition(provider: PositioningProvider, coordinates: Coordinates, floorId: String?, atMs: Long) {}
        override fun onZone(provider: PositioningProvider, zoneId: String, status: ZoneEventStatus, floorId: String?, atMs: Long) {}
        override fun onReport(provider: PositioningProvider, code: SdkErrorCode, context: String) {
            codes += code
        }
        override fun onStoppedUnexpectedly(provider: PositioningProvider, retryable: Boolean, context: String) {
            stops += retryable
        }
    }

    private lateinit var scheduler: TestCoroutineScheduler
    private lateinit var engine: FakeHubEngine
    private lateinit var provider: UwbPositioningProvider
    private val delegate = Recorder()

    @Before fun setUp() {
        SdkLocalized.language = "ko"
        scheduler = TestCoroutineScheduler()
        engine = FakeHubEngine()
        provider = UwbPositioningProvider.create(engine, UnconfinedTestDispatcher(scheduler), clock = { 0L })
        provider.license = "lic"
        provider.delegate = delegate
    }

    /** 시작 안에서 라이선스 거부(10) — 그 자리에서 코어에 「사람이 풀 원인」으로 알리고, 다시 켤 수 있게 IDLE 로 돌아온다. */
    @Test fun syncLicenseRejectionNotifiesCoreInsideStart() {
        engine.onStartSync = { it.onError(10, "license rejected") }
        provider.start()

        assertEquals("start() 가 끝나기 전에 코어가 알아야 한다", listOf(false), delegate.stops)
        assertEquals(listOf(SdkErrorCode.KEY_UNAVAILABLE), delegate.codes)
        assertFalse(provider.isRunning)
        assertEquals(UwbPositioningProvider.PositioningPhase.IDLE, provider.phase)

        engine.onStartSync = null
        provider.start()
        assertEquals(2, engine.starts)
        assertTrue(provider.isRunning)
    }

    /** 시작 안에서 곧바로 시작됨·층 추적 — 층 미탐지 감시(E3007)를 걸지 않고, 추적 상태로 끝난다. */
    @Test fun syncStartAndTrackingSettleInsideStart() {
        val floors = mutableListOf<Long?>()
        provider.onFloorDetected = FloorDetectedListener { floors += it }
        engine.onStartSync = { l ->
            l.onStarted()
            l.onTrackingStarted(14)
        }
        provider.start()

        assertEquals(UwbPositioningProvider.PositioningPhase.TRACKING, provider.phase)
        assertEquals(14L, provider.detectedFloorId)
        assertEquals(listOf<Long?>(14), floors)
        scheduler.advanceTimeBy(UwbPositioningProvider.FLOOR_DETECT_DELAY_MS + 1)
        scheduler.runCurrent()
        assertFalse("층을 이미 잡았는데 E3007", SdkErrorCode.FLOOR_NOT_DETECTED in delegate.codes)
    }
}
