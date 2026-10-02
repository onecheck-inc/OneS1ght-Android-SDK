package co.onecheck.ones1ght.android.positioning

import co.onecheck.ones1ght.android.model.Coordinates
import co.onecheck.ones1ght.android.model.Position
import co.onecheck.ones1ght.android.model.Zone
import co.onecheck.ones1ght.android.model.ZoneEventStatus
import co.onecheck.ones1ght.android.runtime.SdkLocalized
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 감사 SP-B1 — 엔진이 **스스로** 꺼지면 provider 가 코어에 알린다(onStoppedUnexpectedly, iOS #54
 * didStopUnexpectedly 와 같은 자리). 코어가 stop 한 것·층 탐색만 하던 엔진은 알리지 않는다.
 *
 * retryable(다시 켜 볼 만한가): 엔진 오류 1 라이선스 미등록 · 3 Bluetooth · 7 위치 · 10 라이선스 거부 는
 * iOS 와 같이 false. 안드로이드에만 있는 시작 중단 코드는 9 매니페스트 누락 · 12 미지원 기기 = false(앱을
 * 고치거나 기기를 바꿔야 한다), 11 라이선스 서버 미도달 = true(iOS 11 과 같다), 13 스캔 과다 = true(잠시 뒤 풀린다).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UwbProviderUnexpectedStopTest {

    private class Recorder : PositioningProviderDelegate {
        val stops = mutableListOf<Pair<Boolean, String>>()
        override fun onPosition(provider: PositioningProvider, coordinates: Coordinates, floorId: String?, atMs: Long) {}
        override fun onZone(provider: PositioningProvider, zoneId: String, status: ZoneEventStatus, floorId: String?, atMs: Long) {}
        override fun onEnter(provider: PositioningProvider, buildingId: String) {}
        override fun onStoppedUnexpectedly(provider: PositioningProvider, retryable: Boolean, context: String) {
            stops += retryable to context
        }
    }

    private lateinit var scheduler: TestCoroutineScheduler
    private lateinit var engine: FakeHubEngine
    private lateinit var rec: Recorder
    private lateinit var provider: UwbPositioningProvider

    @Before fun setUp() {
        SdkLocalized.language = "ko"
        scheduler = TestCoroutineScheduler()
        engine = FakeHubEngine()
        rec = Recorder()
        provider = UwbPositioningProvider.create(engine, StandardTestDispatcher(scheduler), clock = { scheduler.currentTime })
        provider.delegate = rec
        provider.license = "lic-0123456789"
        provider.apply("b-1", "14")
        provider.apply(
            PositioningConfig(zones = listOf(Zone("za", "A", listOf(Position(0.0, 0.0), Position(1.0, 0.0), Position(1.0, 1.0))))),
        )
    }

    @After fun tearDown() {
        provider.stop()
        SdkLocalized.language = null
    }

    private fun flush() = scheduler.runCurrent()

    private val hub: HubEngine.Listener get() = engine.current!!

    private fun tracking() {
        provider.start()
        hub.onStarted()
        hub.onTrackingStarted(14)
        flush()
    }

    /** 구동 중 Bluetooth 꺼짐(오류 3) 뒤 자동 정지 — 사람이 풀어야 하므로 retryable=false. */
    @Test fun bluetoothOffWhileTrackingIsNotRetryable() {
        tracking()
        hub.onError(3, "bluetooth unavailable: powered off")
        hub.onStopped()
        flush()
        assertEquals(1, rec.stops.size)
        assertFalse(rec.stops[0].first)
        assertTrue(rec.stops[0].second, "3" in rec.stops[0].second)
    }

    /** 구동 중 다른 오류(5 측위 세션 오류) 뒤 자동 정지 — 다시 켜 볼 만하다. */
    @Test fun otherSelfStopIsRetryable() {
        tracking()
        hub.onError(5, "session error")
        hub.onStopped()
        flush()
        assertEquals(listOf(true), rec.stops.map { it.first })
    }

    /** 시작 단계 중단 — 코드별 retryable. */
    @Test fun startAbortCodesMapToRetryable() {
        val expected = mapOf(1 to false, 3 to false, 7 to false, 9 to false, 10 to false, 11 to true, 12 to false, 13 to true)
        for ((code, retryable) in expected) {
            rec.stops.clear()
            provider.start()
            hub.onError(code, "x")
            flush()
            assertEquals("code=$code", listOf(retryable), rec.stops.map { it.first })
        }
    }

    /** 코어가 stop 한 것은 스스로 멈춘 것이 아니다 — 알리지 않는다. */
    @Test fun requestedStopIsNotReported() {
        tracking()
        provider.stop()
        hub.onStopped()
        flush()
        assertTrue(rec.stops.toString(), rec.stops.isEmpty())
    }

    /** 층 탐색만 하던 엔진(측위 미가동)이 접혀도 코어에 알리지 않는다 — 코어가 켜 달라고 한 적이 없다. */
    @Test fun detectionOnlyAbortIsNotReported() {
        provider.startDetection()
        hub.onError(11, "unreachable")
        flush()
        assertTrue(rec.stops.toString(), rec.stops.isEmpty())
    }

    /** 라이선스가 비어 시작 자체가 접히면(동기) 알린다 — retryable=false. */
    @Test fun emptyLicenseIsReportedNotRetryable() {
        provider.license = " "
        provider.start()
        flush()
        assertEquals(listOf(false), rec.stops.map { it.first })
    }

    /** 엔진 start 가 동기로 던지면 — 권한(SecurityException)은 false, 그 밖의 런타임 예외는 true. */
    @Test fun synchronousStartThrowIsReported() {
        engine.throwOnStart = SecurityException("no RANGING")
        provider.start()
        flush()
        assertEquals(listOf(false), rec.stops.map { it.first })

        rec.stops.clear()
        engine.throwOnStart = IllegalStateException("boom")
        provider.start()
        flush()
        assertEquals(listOf(true), rec.stops.map { it.first })
    }

    /** 구역 재적재로 다시 띄운 엔진이 시작에서 접혀도(11) 알린다 — 가동 중이었으므로. */
    @Test fun reopenAfterReloadFailingIsReported() {
        tracking()
        val first = hub
        provider.reloadGeofences()
        first.onStopped()
        flush()
        hub.onError(11, "unreachable")
        flush()
        assertEquals(listOf(true), rec.stops.map { it.first })
    }
}
