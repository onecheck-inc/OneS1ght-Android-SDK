package co.onecheck.ones1ght.android.positioning

//
//  IntelligenceHubEngine.kt
//  [HubEngine] 의 실기기 구현 — 내장 통합 측위 엔진을 그대로 감싼다.
//
//  · 엔진 인스턴스는 프로세스 싱글턴이다(엔진이 applicationContext 만 붙든다).
//  · 권한 검사·요청은 엔진이 하지 않는다 — 검사만 하고 없으면 onError(3·7) 로 알린다. 요청은
//    OneS1ght.permissions(activity) 몫이다. 그래서 start() 호출부의 MissingPermission 린트는 끈다.
//  · 엔진 타입은 이 파일 밖으로 나가지 않는다(공개 API·다른 클래스에 새지 않게).
//

import android.annotation.SuppressLint
import android.content.Context
import kr.geoplan.android.lib.ihub.IntelligenceHub
import kr.geoplan.android.lib.ihub.listener.HubListener

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
        hub.setListener(listener?.let(::Bridge))
    }

    // 권한은 엔진이 검사해 onError(3·7) 로 알린다 — 여기서 미리 막으면 그 사유가 사라진다.
    @SuppressLint("MissingPermission")
    override fun start() {
        hub.start()
    }

    override fun stop() {
        hub.stop()
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
