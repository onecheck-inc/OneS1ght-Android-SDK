package co.onecheck.ones1ght.android.runtime

//
//  LiveStreamController.kt
//  실시간 수신(SSE) 연결의 수명 — 언제 붙이고, 어떤 층 필터로, 언제 다시 붙이는가.
//
//  SessionCoordinator 에서 떼어 냈다(감사 SP-C2 · iOS K7 — 같은 이름). 연결 자체(재연결·백오프·파싱)는
//  LiveConfigStream 이 하고, 여기는 "붙어 있어야 하는가 · 필터가 바뀌었는가" 만 본다.
//  스레드: 코어 디스패처에서만 부른다.
//
//  포팅 원본: LiveStreamController.swift(iOS #55).
//

import co.onecheck.ones1ght.android.model.ConfigChange
import co.onecheck.ones1ght.android.model.FloorState

internal class LiveStreamController(
    /**
     * 실시간 수신 스트림을 만드는 자리. 스트림은 코어 스코프 위에서 onChange·onLog 를 부른다 — 여기서 다시 스레드를
     * 옮기지 않는다. null 을 돌려주면 스트림 없이 돈다.
     */
    private val factory: (onChange: (ConfigChange) -> Unit, onLog: (LogLevel, String) -> Unit) -> LiveConfigStream?,
    private val onChange: (ConfigChange) -> Unit,
    private val onLog: (LogLevel, String) -> Unit,
) {
    private var live: LiveConfigStream? = null

    /** 지금 붙어 있는 스트림이 어떤 층으로 구독했는지 — 재연결 여부 판단용. */
    private var liveFilter: FloorState? = null

    /** 스트림이 붙어 있는가 (테스트·진단용). */
    val isAttached: Boolean get() = live != null

    /**
     * 필요하면 붙이고, 필터가 그대로면 아무것도 하지 않는다(멱등) — 세션 시작·층 지정·포그라운드 복귀가 겹쳐
     * 불려도 연결이 요동치지 않는다. [wanted] 가 아니면 연결만 끊는다(마지막 필터는 기억한다).
     * ⚠️ 필터가 바뀌면 기존 연결을 먼저 끊는다 — 안 그러면 층 전환마다 이전 연결이 옛 필터를 문 채 남는다.
     */
    fun ensure(wanted: Boolean, floor: FloorState?) {
        if (!wanted) {
            live?.stop()
            live = null
            return
        }
        if (live != null && !filterChanged(liveFilter, floor)) return
        live?.stop()
        val s = factory(onChange, onLog)
        s?.start(floor?.buildingId, floor?.floorId)
        live = s
        liveFilter = floor
    }

    /** 백그라운드 — 연결만 끊는다(참조는 둔다). 복귀 때 [forgetConnection] 뒤 [ensure] 가 새로 붙인다. */
    fun suspend() {
        live?.stop()
    }

    /** 배경에서 끊긴 연결을 확실히 닫고 잊는다 — 다음 [ensure] 가 같은 필터여도 새로 붙인다(중복 통지로 연결이 새지 않게). */
    fun forgetConnection() {
        live?.stop()
        live = null
    }

    /** 완전히 끊는다(필터도 잊는다). */
    fun detach() {
        live?.stop()
        live = null
        liveFilter = null
    }

    internal companion object {
        /**
         * 스트림을 붙여 둘 조건 — **층이 정해졌거나 측위가 도는 동안**. 층을 띄워 둔 기기는 이미 "쓰고 있는" 기기라
         * 연결 수는 여전히 유계다(사양서 §4.3).
         */
        fun streamWanted(floorSet: Boolean, running: Boolean): Boolean = floorSet || running

        /**
         * 스트림이 다시 구독해야 할 만큼 건물·층이 바뀌었는지 — buildingId·floorId 만 본다.
         * 같은 층이면 zones 등 나머지가 바뀌어도 구독은 그대로다(그건 refreshZones 의 몫).
         */
        fun filterChanged(previous: FloorState?, next: FloorState?): Boolean =
            previous?.buildingId != next?.buildingId || previous?.floorId != next?.floorId
    }
}
