package co.onecheck.ones1ght.android.positioning

//
//  RangingEngine.kt
//  레인징 세션 + 블록 누적 + 좌표 계산을 감싼 경계 — android.* 를 모른다.
//
//  실제 구현(`UwbRangingEngine`)은 android.ranging 세션과 좌표 엔진을 쓰고, JVM 단위테스트는
//  가짜로 갈아 끼운다. 오케스트레이션(상태 기계·진단·판정 연결·오류 매핑)은 전부
//  [UwbPositioningProvider] 에 있고, 이 경계 뒤에는 "열고·닫고·결과를 올리는" 일만 남는다.
//

internal interface RangingEngine {

    /**
     * DL-TDoA 세션을 연다. [listener] 콜백은 **아무 스레드에서나** 온다(엔진 스레드).
     *
     * @throws SecurityException 권한(RANGING 등)이 없다 → E2003
     * @throws UnsupportedOperationException 이 기기·OS 에서 DL-TDoA 레인징을 할 수 없다 → E2002
     */
    fun open(sessionId: Int, listener: Listener)

    /**
     * 열린(또는 여는 중인) 세션을 닫는다. 이후 [Listener.onClosed] 가 정확히 1회 와야 한다 —
     * 상태 기계가 STOPPING 에서 빠져나오는 유일한 신호다.
     */
    fun close()

    /** 앵커 좌표(짧은주소 → x,y,z 미터) 주입. 세션 중에도 불릴 수 있다. */
    fun applyAnchors(anchors: Map<Int, DoubleArray>)

    /** 엔진 → provider 콜백. 전부 엔진 스레드에서 온다. */
    interface Listener {
        fun onOpened()
        fun onOpenFailed(reason: Int)
        fun onClosed(reason: Int)

        /** 앵커 신호 1건(짧은주소). 측정마다 온다. */
        fun onAnchorSeen(address: Int)

        /** 블록 1개로 산출한 좌표(도면 로컬 미터). */
        fun onPosition(x: Double, y: Double, z: Double)
    }
}
