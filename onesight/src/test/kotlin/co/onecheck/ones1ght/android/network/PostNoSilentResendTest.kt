package co.onecheck.ones1ght.android.network

import co.onecheck.ones1ght.android.model.ReqZoneEvent
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test

/**
 * 한 번 보낸 POST 는 OkHttp 가 조용히 다시 보내지 않는다 — 운영과 같은 기본 클라이언트([ApiClient.create] 기본값)로 본다.
 *
 * 재사용하던 연결이 응답 전에 끊기면 OkHttp 기본값은 새 연결로 요청을 다시 보낸다. 서버는 이미 받았으므로 구역 이벤트가
 * 두 번 쌓인다(2026-10-02). POST 는 한 번만 가고 SDK 가 Network 오류로 받아야 한다. GET 은 멱등이라 예전처럼 복구된다.
 */
class PostNoSilentResendTest {

    @get:Rule val server = MockWebServer()

    // 127.0.0.1 — "localhost" 는 IPv6 경로로도 풀려 재시도 경로가 달라진다(CoordinatorTestSupport 참고).
    private fun client() = ApiClient.create("k", server.url("/api/sdk/v1").newBuilder().host("127.0.0.1").build().toString().trimEnd('/'))

    private val zoneEvent = ReqZoneEvent(
        profileId = "A", visitorId = "V", floorId = "F", zoneId = "Z",
        status = "IN", occurredAt = "2026-10-02T00:00:00Z", platformName = "Android",
    )

    @Test fun postOnReusedConnectionIsNotResentWhenDroppedAfterRequest() = runTest {
        val c = client()
        // ① 연결을 하나 열어 풀에 남긴다
        server.enqueue(MockResponse().setBody("""{"positioning_enabled":true}"""))
        c.config()
        // ② 그 연결로 POST — 서버는 요청을 읽고 끊는다. 뒤에 성공 응답을 하나 더 대기시켜, 몰래 다시 보내면 성공해 버리게 한다.
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        server.enqueue(MockResponse().setBody("""{"accepted":true,"event_id":"e","triggers":[]}"""))
        try {
            c.sendZoneEvent(zoneEvent)
            fail("끊긴 POST 가 성공으로 끝났다 — OkHttp 가 조용히 다시 보냈다")
        } catch (e: ApiError.Network) {
            // 기대 — SDK 가 실패를 알고 자기 규칙(1회 재시도)대로 처리한다
        }
        assertEquals("config 1 + POST 1 — 다시 보내지 않는다", 2, server.requestCount)
    }

    @Test fun getOnReusedConnectionStillRecovers() = runTest {
        val c = client()
        server.enqueue(MockResponse().setBody("""{"positioning_enabled":true}"""))
        c.config()
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        server.enqueue(MockResponse().setBody("""{"positioning_enabled":true}"""))
        c.config() // 낡은 연결 복구는 그대로 — 예외 없이 끝나야 한다
        assertTrue("GET 은 새 연결로 한 번 더 간다", server.requestCount == 3)
    }
}
