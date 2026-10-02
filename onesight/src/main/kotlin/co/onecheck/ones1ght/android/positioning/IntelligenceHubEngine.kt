package co.onecheck.ones1ght.android.positioning

//
//  IntelligenceHubEngine.kt
//  [HubEngine] 의 실기기 구현 — 내장 통합 측위 엔진을 그대로 감싼다.
//
//  · 엔진 인스턴스는 프로세스 싱글턴이다(엔진이 applicationContext 만 붙든다). 그래서 리스너는 엔진에 직접
//    덮어쓰지 않고 프로세스 공용 [HubListenerMux] 에 이 래퍼(=provider 하나) 몫으로 건다(감사 SP-B5).
//  · 권한 검사·요청은 엔진이 하지 않는다 — 검사만 하고 없으면 onError(3·7) 로 알린다. 요청은
//    OneS1ght.permissions(activity) 몫이다. 그래서 start() 호출부의 MissingPermission 린트는 끈다.
//  · 엔진 타입은 이 파일 밖으로 나가지 않는다(공개 API·다른 클래스에 새지 않게).
//  · 엔진은 API 37(android.ranging)부터 돈다. 패키지 minSdk 는 26 이라 이 클래스는 @RequiresApi(37) 로
//    묶는다 — 게이트(SDK_INT 검사) 없이 만드는 곳이 생기면 lint NewApi 가 잡는다. 이 클래스를 만들기
//    전까지는 엔진 클래스가 하나도 로드되지 않는다(엔진 타입이 이 파일 밖으로 안 나가는 이유이기도 하다).
//

import android.annotation.SuppressLint
import android.content.Context
import androidx.annotation.RequiresApi
import co.onecheck.ones1ght.android.MIN_POSITIONING_SDK
import kr.geoplan.android.lib.ihub.IntelligenceHub
import kr.geoplan.android.lib.ihub.listener.HubListener

@RequiresApi(MIN_POSITIONING_SDK)
internal class IntelligenceHubEngine(context: Context) : HubEngine {

    private val appContext: Context = context.applicationContext ?: context

    private val hub: IntelligenceHub by lazy { IntelligenceHub.getInstance(appContext) }

    override val version: String
        get() = hub.libraryVersion ?: "?"

    override val hardwareAvailable: Boolean
        get() = IntelligenceHub.isUwbHardwareAvailable(appContext)

    override fun setLicense(key: String) {
        IntelligenceHub.setLicense(key)
    }

    override fun setListener(listener: HubEngine.Listener?) {
        muxFor(hub).set(owner = this, listener = listener)
    }

    // 권한은 엔진이 검사해 onError(3·7) 로 알린다 — 여기서 미리 막으면 그 사유가 사라진다.
    @SuppressLint("MissingPermission")
    override fun start() {
        hub.start()
    }

    override fun stop() {
        hub.stop()
    }

    private companion object {
        /** 엔진 싱글턴 하나에 멀티플렉서 하나 — 처음 쓸 때 만든다. */
        @Volatile private var mux: HubListenerMux? = null

        @Synchronized
        fun muxFor(hub: IntelligenceHub): HubListenerMux =
            mux ?: HubListenerMux { listener -> hub.setListener(listener?.let(::Bridge)) }.also { mux = it }
    }

    /** 엔진 리스너 → 계약 리스너. 스레드는 옮기지 않는다(provider 가 코어로 넘긴다). */
    private class Bridge(private val target: HubEngine.Listener) : HubListener {
        override fun onStarted() = target.onStarted()
        override fun onStopped() = target.onStopped()
        override fun onTrackingStarted(floorId: Long) = target.onTrackingStarted(floorId)
        override fun onTrackingStopped(floorId: Long) = target.onTrackingStopped(floorId)
        override fun onPosition(floorId: Long, x: Double, y: Double, z: Double) = target.onPosition(floorId, x, y, z)
        override fun onAreaEvent(floorId: Long, areaName: String?, inOut: String?) =
            target.onAreaEvent(floorId, areaName.orEmpty(), inOut.orEmpty())
        override fun onError(code: Int, msg: String?) = target.onError(code, msg.orEmpty())
    }
}
