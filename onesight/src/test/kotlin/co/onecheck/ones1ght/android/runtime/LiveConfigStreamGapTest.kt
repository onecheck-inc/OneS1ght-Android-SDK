package co.onecheck.ones1ght.android.runtime

import co.onecheck.ones1ght.android.model.ConfigChange
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 갭 판정 — 이벤트를 놓쳤다면 ResyncNeeded 로 알린다.
 * (연결·재연결은 [LiveConfigStreamIngestTest] 대상이고, 여기서는 판정 규칙만 못 박는다 —
 * `ingest`/`onConnected` 를 네트워크 없이 직접 부른다.)
 *
 * 포팅 원본: LiveConfigStreamGapTests.swift.
 */
class LiveConfigStreamGapTest {

    private fun stream(onChange: (ConfigChange) -> Unit): LiveConfigStream =
        LiveConfigStream(
            http = OkHttpClient(),
            baseUrl = "https://example.test/api/sdk/v1",
            apiKey = "ock_test",
            scope = CoroutineScope(Dispatchers.Unconfined),
            onChange = onChange,
            log = { _, _ -> },
        )

    private fun frame(event: String, seq: Int): SseFrame =
        SseFrame(event = event, data = "{\"seq\":$seq,\"tenant_id\":7,\"floor_id\":\"f-1\"}")

    @Test
    fun consecutiveSeqDoesNotAskForResync() {
        val got = mutableListOf<ConfigChange>()
        val s = stream { got.add(it) }

        s.ingest(frame("hello", 10))
        s.ingest(frame("zones.changed", 11))
        s.ingest(frame("zones.changed", 12))

        assertFalse(got.contains(ConfigChange.ResyncNeeded))
        assertEquals(
            listOf(ConfigChange.ZonesChanged(floorId = "f-1"), ConfigChange.ZonesChanged(floorId = "f-1")),
            got,
        )
    }

    @Test
    fun seqGapAsksForResync() {
        val got = mutableListOf<ConfigChange>()
        val s = stream { got.add(it) }

        s.ingest(frame("hello", 10))
        s.ingest(frame("zones.changed", 14)) // 11·12·13 을 놓쳤다

        assertEquals(ConfigChange.ResyncNeeded, got.first()) // 놓친 것을 먼저 알린다
        assertTrue(got.contains(ConfigChange.ZonesChanged(floorId = "f-1")))
    }

    @Test
    fun seqGoingBackwardsAlsoAsksForResync() {
        // Redis 가 초기화되면 seq 가 1 로 되감긴다 — 안전한 방향으로 틀려야 한다.
        val got = mutableListOf<ConfigChange>()
        val s = stream { got.add(it) }

        s.ingest(frame("hello", 500))
        s.ingest(frame("zones.changed", 1))

        assertEquals(ConfigChange.ResyncNeeded, got.first())
    }

    @Test
    fun helloAloneDeliversNothing() {
        // 연결 직후 기준선만 잡는다 — 그 자체로는 고객사에게 알릴 변경이 없다.
        val got = mutableListOf<ConfigChange>()
        val s = stream { got.add(it) }

        s.ingest(frame("hello", 1))

        assertTrue(got.isEmpty())
    }

    @Test
    fun onConnectedResetsLastSeqAndSignalsResync() {
        // onConnected() 이후에는 lastSeq 가 리셋되므로, 이전 seq 와 안 이어져도 갭이 아니다.
        val got = mutableListOf<ConfigChange>()
        val s = stream { got.add(it) }

        s.ingest(frame("zones.changed", 50))
        got.clear()

        s.onConnected()
        assertEquals(listOf(ConfigChange.ResyncNeeded), got)
        got.clear()

        s.ingest(frame("zones.changed", 1)) // 리셋됐으니 50 → 1 은 갭이 아니다

        assertFalse(got.contains(ConfigChange.ResyncNeeded))
        assertEquals(listOf(ConfigChange.ZonesChanged(floorId = "f-1")), got)
    }
}
