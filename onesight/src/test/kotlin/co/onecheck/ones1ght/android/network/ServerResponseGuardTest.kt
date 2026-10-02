package co.onecheck.ones1ght.android.network

import co.onecheck.ones1ght.android.model.ReqVerify
import co.onecheck.ones1ght.android.model.ReqZoneEvent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * 서버 응답 방어 — 감사 SF-A4 · SF-A13 · SF-A16 (iOS S17 과 같은 방향: 서버 값 하나 때문에 문서에 없는
 * 예외가 새거나 전체가 실패하지 않게).
 */
class ServerResponseGuardTest {

    @get:Rule val server = MockWebServer()

    private lateinit var client: ApiClient

    @Before
    fun setUp() {
        client = ApiClient.create("test-key", server.url("/api/sdk/v1").toString().trimEnd('/'))
    }

    private suspend inline fun <reified T : Throwable> assertThrowsSuspend(crossinline block: suspend () -> Unit): T {
        try {
            block()
        } catch (e: Throwable) {
            if (e is T) return e
            fail("기대한 예외 타입이 아니다: ${T::class.simpleName} — 실제: $e")
            throw e
        }
        fail("예외가 발생하지 않았다: ${T::class.simpleName}")
        error("unreachable")
    }

    // MARK: - SF-A4 — 객체가 아닌 본문

    /**
     * verify 가 200 에 `null` 을 주면 문서에 있는 [ApiError.Decoding](E5005)이어야 한다. 예전엔 커스텀
     * 시리얼라이저의 `.jsonObject` 가 IllegalArgumentException 을 던져 initialize 가 문서에 없는 예외로 끝났다.
     */
    @Test fun verifyNullBodyIsDecodingError() = runTest {
        server.enqueue(MockResponse().setBody("null"))
        assertThrowsSuspend<ApiError.Decoding> { client.verify(ReqVerify(platformName = "Android")) }
    }

    @Test fun verifyArrayBodyIsDecodingError() = runTest {
        server.enqueue(MockResponse().setBody("[]"))
        assertThrowsSuspend<ApiError.Decoding> { client.verify(ReqVerify(platformName = "Android")) }
    }

    /** 시책 하나가 객체가 아니어도 나머지 시책은 온다 — 요소 단위로 버린다. */
    @Test fun zoneEventSkipsNonObjectTrigger() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """
                {"accepted":true,"event_id":"e-1","triggers":[
                  1, null, "x", {"trigger_id":"t-ok","type":"coupon"}, {"type":"no-id"}
                ]}
                """.trimIndent(),
            ),
        )
        val res = client.sendZoneEvent(zoneEvent())
        // 객체가 아닌 것만 버린다. id 가 빠진 시책은 빈 id 로 산다(iOS #55 — 쿠폰을 그리는 데 필요한 것은 payload).
        assertEquals(listOf("t-ok", ""), res.triggers.map { it.triggerId })
        assertEquals("no-id", res.triggers[1].type)
    }

    // MARK: - SF-A16 — 경로 변수 인코딩

    /** ID 에 `/ ? #` 이 들어가도 다른 엔드포인트를 치거나 잘리지 않는다 — 한 경로 조각으로 인코딩된다. */
    @Test fun profileIdIsEncodedAsOnePathSegment() = runTest {
        server.enqueue(MockResponse().setBody("""{"profile_id":"x","attributes":{}}"""))
        client.getProfile("a/../../auth/verify?x=1#f")
        val path = server.takeRequest().path!!
        assertEquals("/api/sdk/v1/profiles/a%2F..%2F..%2Fauth%2Fverify%3Fx=1%23f", path)
    }

    /** `.`·`..`·빈 ID 는 URL 정규화가 경로를 올려 버리므로 보내지 않는다 — 그런 프로필은 없다(NotFound). */
    @Test fun dotSegmentIdIsRejectedWithoutRequest() = runTest {
        for (id in listOf("..", ".", "")) {
            assertThrowsSuspend<ApiError.NotFound> { client.getProfile(id) }
        }
        assertEquals(0, server.requestCount)
    }

    // MARK: - SF-A13 — 응답 크기 상한 · 디코딩은 메인 밖에서

    /** 상한을 넘는 본문은 끝까지 읽지 않고 E5005 로 끊는다(수 MB 도면이 OOM 으로 가지 않게). */
    @Test fun oversizedBodyIsRejected() = runTest {
        server.enqueue(MockResponse().setBody("x".repeat(4_096)))
        val req = Request.Builder().url(server.url("/big")).build()
        val e = assertThrowsSuspend<ApiError.Decoding> { executeHttpRequest(OkHttpClient(), req, maxBytes = 1_024) }
        assertTrue("${e.detail}", e.detail.orEmpty().contains("1024"))
    }

    @Test fun bodyAtLimitIsAccepted() = runTest {
        server.enqueue(MockResponse().setBody("x".repeat(1_024)))
        val req = Request.Builder().url(server.url("/ok")).build()
        val (status, bytes) = executeHttpRequest(OkHttpClient(), req, maxBytes = 1_024)
        assertEquals(200, status)
        assertEquals(1_024, bytes.size)
    }

    /** JSON 디코딩은 부른 스레드(운영: 메인)가 아니라 Default 디스패처에서 돈다. */
    @Test fun decodingRunsOffCallerThread() = runTest {
        server.enqueue(MockResponse().setBody("\"hello\""))
        val caller = Thread.currentThread().name
        val req = Request.Builder().url(server.url("/t")).build()
        val probe = performJsonRequest<ThreadProbe>(OkHttpClient(), req)
        assertFalse("디코딩이 부른 스레드($caller)에서 돌았다", probe.thread == caller)
        assertTrue(probe.thread, probe.thread.contains("DefaultDispatcher"))
    }

    private fun zoneEvent() = ReqZoneEvent(
        profileId = "p",
        visitorId = "v",
        floorId = "f",
        zoneId = "z",
        status = "IN",
        occurredAt = "2026-10-02T00:00:00Z",
        platformName = "Android",
    )
}

/** 디코딩이 어느 스레드에서 돌았는지 담는다. */
@Serializable(with = ThreadProbeSerializer::class)
internal data class ThreadProbe(val thread: String)

internal object ThreadProbeSerializer : KSerializer<ThreadProbe> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("ThreadProbe", PrimitiveKind.STRING)
    override fun deserialize(decoder: Decoder): ThreadProbe {
        decoder.decodeString()
        return ThreadProbe(Thread.currentThread().name)
    }
    override fun serialize(encoder: Encoder, value: ThreadProbe) = encoder.encodeString(value.thread)
}
