package co.onecheck.ones1ght.android.positioning

//
//  HubListenerMux.kt
//  측위 엔진 리스너 멀티플렉서 — 엔진은 프로세스 싱글턴인데 provider 는 여럿일 수 있다.
//
//  예전엔 provider 마다 엔진에 자기 리스너를 덮어써, 나중에 붙은 쪽이 앞쪽의 콜백을 가로챘다(앱이 만든 provider
//  로 층을 찾는 중에 begin() 의 내장 provider 가 붙으면 앞 provider 는 콜백이 끊겨 E3007 오탐 — 감사 SP-B5).
//  이제 엔진에는 이 멀티플렉서 하나만 붙고, 붙은 provider(owner) 모두에게 같은 콜백을 나눠 준다. 각 provider 는
//  자기 세대 가드로 늦은 콜백을 거른다. 붙은 provider 가 하나도 없으면 엔진 리스너도 뗀다.
//

import java.util.concurrent.CopyOnWriteArrayList

/** [attach] — 엔진에 리스너를 붙이거나(null 이면) 뗀다. 콜백은 엔진 스레드에서 오므로 목록은 스레드 안전하게 둔다. */
internal class HubListenerMux(private val attach: (HubEngine.Listener?) -> Unit) {

    private class Slot(val owner: Any, val listener: HubEngine.Listener)

    private val slots = CopyOnWriteArrayList<Slot>()
    private var attached = false

    /** [owner] 의 리스너를 바꾸거나(같은 owner 는 하나만) 뗀다(null). */
    @Synchronized
    fun set(owner: Any, listener: HubEngine.Listener?) {
        slots.removeAll { it.owner === owner }
        if (listener != null) slots += Slot(owner, listener)
        if (slots.isEmpty() && attached) {
            attached = false
            attach(null)
        } else if (slots.isNotEmpty() && !attached) {
            attached = true
            attach(fanOut)
        }
    }

    private val fanOut = object : HubEngine.Listener {
        override fun onStarted() = slots.forEach { it.listener.onStarted() }
        override fun onStopped() = slots.forEach { it.listener.onStopped() }
        override fun onTrackingStarted(floorId: Long) = slots.forEach { it.listener.onTrackingStarted(floorId) }
        override fun onTrackingStopped(floorId: Long) = slots.forEach { it.listener.onTrackingStopped(floorId) }
        override fun onPosition(floorId: Long, x: Double, y: Double, z: Double) =
            slots.forEach { it.listener.onPosition(floorId, x, y, z) }
        override fun onAreaEvent(floorId: Long, areaName: String, inOut: String) =
            slots.forEach { it.listener.onAreaEvent(floorId, areaName, inOut) }
        override fun onError(code: Int, message: String) = slots.forEach { it.listener.onError(code, message) }
    }
}
