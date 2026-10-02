package co.onecheck.ones1ght.android.positioning

import co.onecheck.ones1ght.android.runtime.SdkLocalized
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 엔진 상태 기계의 고착 — 감사 SP-B3 · SP-B4 · SP-B14, iOS S8(회귀 방지).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProviderStateMachineTest {

    /** start() 안에서 **동기로** 오류를 주는 엔진 — 실제 엔진의 「미지원 기기」·OS 미달 자리표시자와 같다. */
    private class SyncFailingEngine(private val code: Int, private val message: String) : HubEngine {
        private var registered: HubEngine.Listener? = null
        var starts = 0
        override val version: String = "t"
        override val hardwareAvailable: Boolean = true
        override fun setLicense(key: String) {}
        override fun setListener(listener: HubEngine.Listener?) {
            registered = listener
        }
        override fun start() {
            starts += 1
            registered?.onError(code, message)
        }
        override fun stop() {
            registered?.onStopped()
        }
    }

    private lateinit var scheduler: TestCoroutineScheduler

    @Before fun setUp() {
        SdkLocalized.language = "ko"
        scheduler = TestCoroutineScheduler()
    }

    /**
     * SP-B3 — 엔진 콜백이 같은 스레드에서 곧바로 오면(운영 디스패처 Main.immediate) 시작 중 오류 12 가 IDLE 로
     * 정리한 뒤에 isRunning=true 가 덮여, 이후 start() 가 전부 no-op 이었다.
     */
    @Test fun synchronousStartAbortLeavesProviderRestartable() {
        val engine = SyncFailingEngine(12, "dl-tdoa is not supported on this device")
        val provider = UwbPositioningProvider.create(engine, UnconfinedTestDispatcher(scheduler), clock = { 0L })
        provider.license = "lic"
        provider.start()
        assertFalse("시작이 접혔는데 측위 중으로 남았다", provider.isRunning)
        assertEquals(UwbPositioningProvider.PositioningPhase.IDLE, provider.phase)
        provider.start()
        assertEquals("두 번째 start 가 삼켜졌다", 2, engine.starts)
    }

    /**
     * SP-B4 — 층 탐색 전용(startDetection)으로 띄웠는데 엔진이 「정지 중(8)」을 주면, 측위 중이 아니라는 이유로
     * 다시 열기가 막혀 STARTING 에 영원히 머물렀다. 이제 정지가 끝나는 대로 다시 연다.
     */
    @Test fun detectionOnlyStartRetriesAfterEngineStopping() {
        val engine = FakeHubEngine()
        val provider = UwbPositioningProvider.create(engine, StandardTestDispatcher(scheduler), clock = { 0L })
        provider.license = "lic"
        provider.startDetection()
        assertEquals(1, engine.starts)
        engine.current!!.onError(8, "stopping")
        scheduler.runCurrent()
        assertEquals("정지를 기다리지 않고 STARTING 에 굳었다", UwbPositioningProvider.PositioningPhase.STOPPING, provider.phase)
        engine.current!!.onStopped()
        scheduler.runCurrent()
        assertEquals("정지가 끝났으면 다시 띄워야 한다", 2, engine.starts)
        assertEquals(UwbPositioningProvider.PositioningPhase.STARTING, provider.phase)
        assertFalse("층 탐색 전용은 측위를 켜지 않는다", provider.isRunning)
    }

    /**
     * SP-B14 — 시작 직후 끄면(STOPPING) 그 뒤에 온 「시작 중단」 오류를 무시하고 감시 타이머 5초를 꼭 기다렸다.
     * 시작이 접혔으면 onStopped 는 오지 않는다 — 곧바로 멈춘 것으로 친다.
     */
    @Test fun startAbortWhileStoppingFinishesImmediately() {
        val engine = FakeHubEngine()
        val provider = UwbPositioningProvider.create(engine, StandardTestDispatcher(scheduler), clock = { 0L })
        provider.license = "lic"
        provider.start()
        val listener = engine.current!!
        provider.stop()
        assertEquals(UwbPositioningProvider.PositioningPhase.STOPPING, provider.phase)
        listener.onError(10, "license is invalid")
        scheduler.runCurrent()
        assertEquals("5초 감시를 기다리지 않고 멈춰야 한다", UwbPositioningProvider.PositioningPhase.IDLE, provider.phase)
        provider.start()
        assertEquals(2, engine.starts)
    }

    /** iOS S8(안드로이드는 이미 안전 — 회귀 방지) — 구역 재적재 중에 끄면 엔진이 다시 켜지지 않는다. */
    @Test fun stopDuringGeofenceReloadDoesNotRestart() {
        val engine = FakeHubEngine()
        val provider = UwbPositioningProvider.create(engine, StandardTestDispatcher(scheduler), clock = { 0L })
        provider.license = "lic"
        provider.start()
        engine.current!!.onStarted()
        scheduler.runCurrent()
        provider.reloadGeofences()
        assertEquals(UwbPositioningProvider.PositioningPhase.STOPPING, provider.phase)
        provider.stop()
        engine.current!!.onStopped()
        scheduler.runCurrent()
        assertEquals(UwbPositioningProvider.PositioningPhase.IDLE, provider.phase)
        assertEquals("끈 뒤에 재적재가 엔진을 다시 켰다", 1, engine.starts)
        assertTrue(!provider.isRunning)
    }
}
