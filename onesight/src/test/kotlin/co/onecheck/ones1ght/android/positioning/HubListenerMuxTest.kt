package co.onecheck.ones1ght.android.positioning

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 감사 SP-B5 — 측위 엔진은 프로세스 싱글턴인데 provider 는 여럿 만들 수 있다(앱이 만든 것 + begin() 의 내장).
 * 예전엔 각자 엔진 리스너를 덮어써, 나중에 붙은 쪽이 앞쪽의 콜백을 가로챘다(앞 provider 는 E3007 오탐).
 * 이제 엔진에는 멀티플렉서 하나만 붙고, 붙은 provider 모두에게 나눠 준다.
 */
class HubListenerMuxTest {

    private class Rec : HubEngine.Listener {
        val calls = mutableListOf<String>()
        override fun onStarted() { calls += "started" }
        override fun onStopped() { calls += "stopped" }
        override fun onTrackingStarted(floorId: Long) { calls += "track:$floorId" }
        override fun onTrackingStopped(floorId: Long) { calls += "untrack:$floorId" }
        override fun onPosition(floorId: Long, x: Double, y: Double, z: Double) { calls += "pos" }
        override fun onAreaEvent(floorId: Long, areaName: String, inOut: String) { calls += "$inOut:$areaName" }
        override fun onError(code: Int, message: String) { calls += "err:$code" }
    }

    @Test fun everyRegisteredListenerGetsCallbacks() {
        var attached: HubEngine.Listener? = null
        val mux = HubListenerMux { attached = it }
        val a = Rec()
        val b = Rec()
        mux.set(owner = "a", listener = a)
        mux.set(owner = "b", listener = b)
        attached!!.onTrackingStarted(14)
        assertEquals(listOf("track:14"), a.calls)
        assertEquals(listOf("track:14"), b.calls)
    }

    @Test fun replacingOrRemovingOneKeepsTheOther() {
        var attached: HubEngine.Listener? = null
        val mux = HubListenerMux { attached = it }
        val a1 = Rec()
        val a2 = Rec()
        val b = Rec()
        mux.set("a", a1)
        mux.set("b", b)
        mux.set("a", a2) // 같은 provider 의 새 세대
        mux.set("b", null)
        assertNotNull(attached)
        attached!!.onError(3, "x")
        assertEquals(emptyList<String>(), a1.calls)
        assertEquals(listOf("err:3"), a2.calls)
        assertEquals(emptyList<String>(), b.calls)
        mux.set("a", null)
        assertNull("붙은 provider 가 없으면 엔진 리스너도 뗀다", attached)
    }
}
