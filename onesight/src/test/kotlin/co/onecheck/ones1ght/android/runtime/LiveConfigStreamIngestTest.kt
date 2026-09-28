package co.onecheck.ones1ght.android.runtime

import co.onecheck.ones1ght.android.model.ConfigChange
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 실제 수신 경로 — 네트워크 바이트가 [ConfigChange] 로 나오기까지.
 *
 * 이 테스트가 없으면 실기기에서 한참을 헤맬 수 있다. 연결 직후의 ResyncNeeded 는 파서를
 * 거치지 않고 나가기 때문에 "연결됨" 로그와 첫 갱신은 정상으로 보이지만, 프레임 조립이
 * 깨지면 zones.changed 같은 실제 이벤트는 하나도 도착하지 않는다 — 증상은 "새로고침을
 * 눌러야 갱신된다" 뿐이라 원인이 잘 드러나지 않는다. 그래서 여기서 지키는 것은
 * **프레임이 실제로 조립되는가** 다. 페이로드는 iOS 쪽 테스트가 옮겨둔 2026-08-25 prod
 * `GET /api/sdk/v1/stream` 응답을 그대로 쓴다 — 하트비트 주석과 빈 줄 구분까지 포함해서.
 *
 * 포팅 원본: LiveConfigStreamIngestTests.swift.
 */
class LiveConfigStreamIngestTest {

    @get:Rule val server = MockWebServer()

    // 운영의 코어 디스패처(Main)처럼 한 줄로 도는 디스패처 — 스트림은 받은 것을 이 스코프로 넘기므로
    // 여러 스레드 디스패처면 도착 순서가 흔들린다.
    private val scope = CoroutineScope(Dispatchers.IO.limitedParallelism(1) + SupervisorJob())

    @After
    fun tearDown() {
        scope.cancel()
    }

    /** prod 실응답. 프레임 사이는 빈 줄 하나로 구분된다(콜론 뒤 공백 없음 — 실제 포맷). */
    private val realStream =
        "event:hello\n" +
            "data:{\"seq\":36,\"tenant_id\":1}\n" +
            "\n" +
            ":ping\n" +
            "\n" +
            "event:zones.changed\n" +
            "data:{\"seq\":37,\"tenant_id\":1,\"store_id\":3,\"building_id\":\"b-1\",\"floor_id\":\"14\"," +
            "\"origin\":\"agent.execute\"}\n" +
            "\n" +
            "event:rules.changed\n" +
            "data:{\"seq\":38,\"tenant_id\":1,\"store_id\":3,\"zone_id\":264,\"rule_id\":120,\"status\":\"active\"}\n" +
            "\n"

    private fun stream(onChange: (ConfigChange) -> Unit): LiveConfigStream =
        LiveConfigStream(
            http = OkHttpClient(),
            baseUrl = server.url("/api/sdk/v1").toString().trimEnd('/'),
            apiKey = "ock_test",
            scope = scope,
            onChange = onChange,
            log = { _, _ -> },
        )

    /** 핵심 — 서버가 보낸 이벤트가 호스트까지 도착해야 한다. */
    @Test
    fun deliversEventsFromRealServerPayload() {
        server.enqueue(MockResponse().setBody(realStream))

        val got = mutableListOf<ConfigChange>()
        val done = CountDownLatch(3) // resyncNeeded(연결) + zones.changed + rules.changed
        val liveConfigStream = stream { change ->
            got.add(change)
            done.countDown()
        }

        liveConfigStream.start(buildingId = "b-1", floorId = "14")
        assertTrue(done.await(5, TimeUnit.SECONDS))
        liveConfigStream.stop()

        // 연결 자체가 올리는 신호는 파서를 거치지 않는다 — 이게 통과한다고 수신이 되는 게 아니다.
        assertEquals(ConfigChange.ResyncNeeded, got[0])
        // 진짜로 확인해야 하는 것: 파서를 거쳐 온 이벤트들.
        assertTrue("zones.changed 가 도착하지 않았다 — 프레임 조립이 깨졌다", got.contains(ConfigChange.ZonesChanged(floorId = "14")))
        assertTrue("rules.changed 가 도착하지 않았다", got.contains(ConfigChange.RulesChanged(zoneId = "264")))

        val req = server.takeRequest()
        assertEquals("GET", req.method)
        assertEquals("/api/sdk/v1/stream?buildingId=b-1&floorId=14", req.path)
        assertEquals("ock_test", req.getHeader("X-SDK-Key"))
        assertEquals("text/event-stream", req.getHeader("Accept"))
    }

    /** 하트비트 주석만 오는 동안에는 호스트에 아무것도 올리지 않는다. */
    @Test
    fun heartbeatAloneDeliversNothingBeyondConnect() {
        server.enqueue(MockResponse().setBody(":ping\n\n:ping\n\n"))

        val got = mutableListOf<ConfigChange>()
        val firstChange = CountDownLatch(1)
        val liveConfigStream = stream { change ->
            got.add(change)
            firstChange.countDown()
        }

        liveConfigStream.start(buildingId = null, floorId = null)
        assertTrue(firstChange.await(5, TimeUnit.SECONDS))
        Thread.sleep(300) // 최소 재연결 백오프(1초 근방)보다 한참 짧게 — 하트비트 외에 더 오는지 확인할 여유
        liveConfigStream.stop()

        assertEquals(listOf(ConfigChange.ResyncNeeded), got)

        val req = server.takeRequest()
        assertEquals("/api/sdk/v1/stream", req.path) // null 파라미터는 생략
    }
}
