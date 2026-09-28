package co.onecheck.ones1ght.android

//
//  JavaBridge.kt
//  suspend 판 → Java [Callback] 판 이음새.
//
//  코어 디스패처(운영: Dispatchers.Main.immediate) 위의 SDK 전용 스코프에서 suspend 판을 돌리고,
//  결과·예외를 같은 디스패처(= 메인)에서 콜백으로 넘긴다. 스코프는 SupervisorJob 이라 한 호출의
//  실패가 다른 호출을 끊지 않는다.
//

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

internal object JavaBridge {

    private val job = SupervisorJob()

    /** 아직 콜백까지 끝나지 않은 호출 수 — 테스트가 "다 끝났는가" 를 본다. */
    internal val inFlight = AtomicInteger(0)

    /**
     * [block] 을 코어 디스패처에서 실행하고 결과를 [callback] 으로 넘긴다.
     *
     * 콜백 자체가 던진 예외는 onError 로 되돌리지 않는다 — 성공을 실패로 두 번 알리게 된다.
     * 그 예외는 코루틴 예외 처리기로 올라간다(앱 코드의 버그다).
     */
    fun <T> run(callback: Callback<T>, block: suspend () -> T) {
        inFlight.incrementAndGet()
        try {
            CoroutineScope(job + OneS1ght.dispatcher).launch {
                try {
                    val outcome: Result<T> = try {
                        Result.success(block())
                    } catch (e: CancellationException) {
                        // 우리 스코프가 취소된 것이면 조용히 끝낸다. 아니면(시간 초과 등 본문이 던진
                        // 취소) 콜백이 영영 안 오는 일이 없게 실패로 알린다.
                        if (!isActive) throw e
                        Result.failure(e)
                    } catch (e: Throwable) {
                        Result.failure(e)
                    }
                    outcome.fold(
                        onSuccess = { callback.onSuccess(it) },
                        onFailure = { callback.onError(it) },
                    )
                } finally {
                    inFlight.decrementAndGet()
                }
            }
        } catch (e: Throwable) {
            // 디스패처 조회 실패 등 launch 자체가 못 섰다 — 셈을 되돌리고 그대로 알린다.
            inFlight.decrementAndGet()
            throw e
        }
    }
}
