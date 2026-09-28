package co.onecheck.ones1ght.android.zone

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 운영용 [DwellScheduler] — 주입된 스코프(디스패처) 위에서 `delay` 로 지연 발화한다.
 * `runTest`/`StandardTestDispatcher` 의 가상 시계를 직접 밀어 검증한다(실제 시간을 기다리지
 * 않는다). [scope] 는 호출자와 같은 디스패처를 써야 한다는 계약은 여기서는 `TestScope` 자체가
 * 단일 가상 디스패처이므로 자연히 지켜진다.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CoroutineDwellSchedulerTest {

    @Test
    fun scheduleFiresAfterDelayElapses() =
        runTest {
            val scheduler = CoroutineDwellScheduler(this)
            var fired = 0

            scheduler.schedule(1_000) { fired += 1 }
            advanceTimeBy(1_000)
            runCurrent() // 정확히 그 시각(경계)에 걸린 작업까지 마저 돌린다

            assertEquals(1, fired)
        }

    @Test
    fun cancelPreventsFiring() =
        runTest {
            val scheduler = CoroutineDwellScheduler(this)
            var fired = 0

            val cancellable = scheduler.schedule(1_000) { fired += 1 }
            cancellable.cancel()
            advanceTimeBy(2_000)
            runCurrent()

            assertEquals(0, fired)
        }
}
