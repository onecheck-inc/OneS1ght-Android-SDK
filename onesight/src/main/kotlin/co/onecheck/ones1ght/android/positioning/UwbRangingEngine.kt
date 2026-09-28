package co.onecheck.ones1ght.android.positioning

//
//  UwbRangingEngine.kt
//  [RangingEngine] 의 실기기 구현 — android.ranging DL-TDoA 세션 + 블록 누적 + 좌표 엔진.
//
//  흐름(spec §5):
//    RangingSession(DT-Tag, sessionId) ─► onDlTdoaResults(peer, m)
//        ├─► peer.dlTdoaUwbAddress 하위 2바이트 → listener.onAnchorSeen (진단)
//        └─► DlBlockAccumulator(260ms) ─► DlTdoaPositioner.update(block) ─► listener.onPosition
//
//  스레드: 세션 콜백은 이 클래스의 단일 스레드 executor 에서, 블록 콜백은 누적기 스레드에서
//  온다. 좌표 엔진 호출은 [Api37Session.lock] 으로 직렬화한다. provider 가 메인으로 넘긴다.
//
//  ⚠️ API 37 미만에서는 android.ranging 의 DT-Tag·DL-TDoA 가 없다 — open 이
//     UnsupportedOperationException 을 던지고 provider 가 E2002 로 올린다. API 37 전용 코드는
//     전부 [Api37Session] 안에 가둬 둔다(minSdk 27 기기에서 클래스를 건드리지 않도록).
//

import android.content.Context
import android.os.Build
import android.ranging.DlTdoaMeasurement
import android.ranging.RangingData
import android.ranging.RangingDevice
import android.ranging.RangingManager
import android.ranging.RangingSession
import androidx.annotation.RequiresApi
import kr.geoplan.android.lib.dltdoa.DlBlockAccumulator
import kr.geoplan.android.lib.dltdoa.DlTdoaPositioner
import kr.geoplan.android.lib.dltdoa.PositionCallback
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

internal class UwbRangingEngine(private val context: Context) : RangingEngine {

    private val executor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "ones1ght-ranging").apply { isDaemon = true }
    }

    /** 최근 주입 앵커 — 다음 세션의 좌표 엔진에도 넣는다. */
    @Volatile
    private var anchors: Map<Int, DoubleArray> = emptyMap()

    /** 현재 세션(메인에서만 바꾼다). API 37 미만에서는 항상 null. */
    private var active: Any? = null

    /** 마지막 open 의 listener — 세션 없이 close 가 와도 onClosed 1회 계약을 지키려고 둔다. */
    private var lastListener: RangingEngine.Listener? = null

    override fun open(sessionId: Int, listener: RangingEngine.Listener) {
        if (Build.VERSION.SDK_INT < DlTdoaSessionConfig.MIN_API) {
            throw UnsupportedOperationException("API ${Build.VERSION.SDK_INT} < ${DlTdoaSessionConfig.MIN_API}")
        }
        lastListener = listener
        active = null
        active = Api37Session(context, executor, anchors, listener).also { it.open(sessionId) }
    }

    override fun close() {
        val session = active
        active = null
        if (Build.VERSION.SDK_INT >= DlTdoaSessionConfig.MIN_API && session is Api37Session) {
            session.close()
            return
        }
        // 세션이 없다 — 그래도 상태 기계가 STOPPING 에서 빠져나오도록 onClosed 를 1회 준다.
        val listener = lastListener ?: return
        executor.execute { listener.onClosed(RangingErrorMapping.REASON_LOCAL_REQUEST) }
    }

    override fun applyAnchors(anchors: Map<Int, DoubleArray>) {
        this.anchors = anchors.toMap()
        val session = active
        if (Build.VERSION.SDK_INT >= DlTdoaSessionConfig.MIN_API && session is Api37Session) {
            session.applyAnchors(this.anchors)
        }
    }

    @RequiresApi(DlTdoaSessionConfig.MIN_API)
    private class Api37Session(
        private val context: Context,
        private val executor: ExecutorService,
        anchors: Map<Int, DoubleArray>,
        private val listener: RangingEngine.Listener,
    ) {
        private val lock = Any()

        /** 좌표는 update() 반환값으로 받는다 — 콜백 경로는 쓰지 않는다(이중 전달 방지). */
        private val positioner = DlTdoaPositioner(
            object : PositionCallback {
                override fun onPosition(x: Double, y: Double, z: Double) {}
                override fun onInvalidated(error: String?) {}
            },
        ).apply {
            setMinRssi(DlTdoaSessionConfig.MIN_RSSI)
            if (anchors.isNotEmpty()) applyAnchorCoordinates(anchors)
        }

        private val accumulator = DlBlockAccumulator(DlTdoaSessionConfig.BLOCK_TIMEOUT_MS) { block ->
            val xyz = synchronized(lock) { positioner.update(block) }
            if (xyz != null && xyz.size >= 3) listener.onPosition(xyz[0], xyz[1], xyz[2])
        }

        /** open 이 메인에서 쓰고, onClosed/onOpenFailed(executor)가 놓는다. */
        @Volatile
        private var session: RangingSession? = null

        /** 세션이 이미 내려갔는가(onOpenFailed/onClosed 수신) — executor 가 쓰고 메인이 읽는다. */
        @Volatile
        private var finished = false

        private val callback = object : RangingSession.Callback {
            override fun onOpened() = listener.onOpened()

            override fun onOpenFailed(reason: Int) {
                finish()
                listener.onOpenFailed(reason)
            }

            override fun onClosed(reason: Int) {
                finish()
                listener.onClosed(reason)
            }

            override fun onDlTdoaResults(peer: RangingDevice, measurement: DlTdoaMeasurement) {
                AnchorTracker.shortAddress(peer.dlTdoaUwbAddress?.addressBytes)?.let(listener::onAnchorSeen)
                accumulator.add(peer, measurement)
            }

            override fun onResults(peer: RangingDevice, data: RangingData) {}
            override fun onStarted(peer: RangingDevice, technology: Int) {}
            override fun onStopped(peer: RangingDevice, technology: Int) {}
        }

        /** @throws SecurityException 권한 없음 · UnsupportedOperationException 레인징 서비스 없음 */
        fun open(sessionId: Int) {
            val manager = context.getSystemService(RangingManager::class.java)
                ?: throw UnsupportedOperationException("ranging service unavailable")
            val created = manager.createRangingSession(executor, callback)
                ?: throw UnsupportedOperationException("ranging session unavailable")
            session = created
            try {
                created.start(DlTdoaSessionConfig.preference(sessionId))
            } catch (e: RuntimeException) {
                runCatching { created.close() }
                session = null
                throw e
            }
        }

        fun close() {
            val s = session
            if (s == null || finished) {
                executor.execute { listener.onClosed(RangingErrorMapping.REASON_LOCAL_REQUEST) }
                return
            }
            try {
                s.stop() // → onClosed(REASON_LOCAL_REQUEST)
            } catch (e: RuntimeException) {
                // 이미 내려간 세션 등 — onClosed 가 안 올 수 있으니 직접 1회 준다.
                finished = true
                releaseSession()
                executor.execute { listener.onClosed(RangingErrorMapping.REASON_LOCAL_REQUEST) }
            }
        }

        fun applyAnchors(anchors: Map<Int, DoubleArray>) {
            executor.execute { synchronized(lock) { positioner.applyAnchorCoordinates(anchors) } }
        }

        /** onClosed/onOpenFailed — 내려간 세션의 시스템 자원(RangingSession)까지 놓는다. */
        private fun finish() {
            finished = true
            releaseSession()
            synchronized(lock) {
                runCatching { accumulator.reset() }
                runCatching { positioner.reset() }
            }
        }

        /** stop 으로 끝나도 RangingSession 은 close 해야 풀린다 — 안 하면 세션이 시스템에 남는다. */
        private fun releaseSession() {
            val s = session ?: return
            session = null
            runCatching { s.close() }
        }
    }
}
