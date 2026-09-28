package co.onecheck.ones1ght.android.positioning

//
//  AndroidDeviceCapability.kt
//  [DeviceCapability] 의 실기기 구현 — OS 버전과 RangingManager 의 DL-TDoA 지원 여부.
//
//  · API 37 미만은 칩을 묻지 않는다(물을 API 가 없다) — OneS1ght 가 OS 를 먼저 본다.
//  · 칩 조회는 registerCapabilitiesCallback 의 첫 응답 하나로 판정한다. 5초 안에 안 오면 false.
//  · 답을 받은 경우에만 기억한다. 시간 초과·Context 없음·조회 예외는 "모름" 이라 다음에 다시 묻는다
//    (한 번의 늦은 응답으로 기기를 영영 미지원으로 굳히지 않는다).
//  · 콜백 실행기는 호출 스레드(바인더)다 — 메인이 아니다. 동기 조회(deviceAvailability)가 메인에서
//    결과를 기다려도 교착이 없다.
//

import android.content.Context
import android.os.Build
import android.ranging.RangingCapabilities
import android.ranging.RangingManager
import androidx.annotation.RequiresApi
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

internal class AndroidDeviceCapability(
    /** 지금 아는 applicationContext — initialize·permissions 가 채운다. */
    private val contextProvider: () -> Context?,
) : DeviceCapability {

    override val sdkInt: Int
        get() = Build.VERSION.SDK_INT

    @Volatile
    private var cached: Boolean? = null

    override suspend fun supportsDlTdoa(): Boolean {
        cached?.let { return it }
        if (Build.VERSION.SDK_INT < MIN_RANGING_SDK) return false
        val context = contextProvider() ?: return false // initialize 전 — 모름(기억하지 않는다)
        val answer = withTimeoutOrNull(TIMEOUT_MS) { queryDlTdoa(context) } ?: return false
        cached = answer
        return answer
    }

    /** 첫 능력 응답의 DL-TDoA 여부. 조회 자체가 실패하면 null(모름). */
    @RequiresApi(MIN_RANGING_SDK)
    private suspend fun queryDlTdoa(context: Context): Boolean? {
        val manager = context.getSystemService(RangingManager::class.java) ?: return false
        return suspendCancellableCoroutine { cont ->
            val answered = AtomicBoolean(false)
            val callback = object : RangingManager.RangingCapabilitiesCallback {
                override fun onRangingCapabilities(capabilities: RangingCapabilities) {
                    if (!answered.compareAndSet(false, true)) return
                    runCatching { manager.unregisterCapabilitiesCallback(this) }
                    cont.resume(capabilities.uwbCapabilities?.isDlTdoaSupported == true)
                }
            }
            cont.invokeOnCancellation {
                if (answered.compareAndSet(false, true)) runCatching { manager.unregisterCapabilitiesCallback(callback) }
            }
            try {
                manager.registerCapabilitiesCallback(Executor { it.run() }, callback)
            } catch (e: RuntimeException) {
                if (answered.compareAndSet(false, true)) cont.resume(null)
            }
        }
    }

    private companion object {
        const val MIN_RANGING_SDK = 37
        const val TIMEOUT_MS = 5_000L
    }
}
