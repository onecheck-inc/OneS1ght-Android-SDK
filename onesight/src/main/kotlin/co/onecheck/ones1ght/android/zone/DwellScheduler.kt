package co.onecheck.ones1ght.android.zone

//
//  DwellScheduler.kt
//  체류(DWELL) 1회 발화를 위한 지연 스케줄러 — 판정기([UwbAreaJudge])와 분리해 테스트가 시계를
//  직접 밀 수 있게 한다.
//

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 체류(DWELL) 1회 발화를 위한 지연 스케줄러 계약.
 *
 * ⚠️ [schedule] 이 돌려준 콜백([action])은 판정기를 부르는 스레드와 **같은 스레드(디스패처)** 에서
 * 실행돼야 한다 — 코어 상태는 동기화 없이 단일 디스패처 한 곳에서만 바뀐다는 전제다.
 */
internal fun interface DwellScheduler {
    fun schedule(delayMs: Long, action: () -> Unit): Cancellable
}

/** [DwellScheduler.schedule] 이 돌려주는 취소 핸들. */
internal fun interface Cancellable {
    fun cancel()
}

/**
 * 운영용 [DwellScheduler] — 주입된 [scope] 위에서 코루틴 `delay` 로 지연 발화한다.
 *
 * ⚠️ [scope] 는 판정기 호출자와 같은 디스패처(운영: `Dispatchers.Main.immediate`)를 써야 한다.
 */
internal class CoroutineDwellScheduler(private val scope: CoroutineScope) : DwellScheduler {
    override fun schedule(delayMs: Long, action: () -> Unit): Cancellable {
        val job = scope.launch {
            delay(delayMs)
            action()
        }
        return Cancellable { job.cancel() }
    }
}
