package co.onecheck.ones1ght.android.network

import co.onecheck.ones1ght.android.model.ReqPositionBulk
import co.onecheck.ones1ght.android.model.ReqSdkLogs
import co.onecheck.ones1ght.android.model.ReqVerify
import co.onecheck.ones1ght.android.model.ReqZoneEvent
import co.onecheck.ones1ght.android.model.SdkLogEntry
import co.onecheck.ones1ght.android.runtime.SdkErrorCode
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * URLProtocol 스텁 대신 [MockWebServer] 로 실서버 없이: 경로·메서드·X-SDK-Key·바디 정확성 +
 * 상태코드→에러 매핑. 포팅 원본: ApiClientTests.swift.
 */
class ApiClientTest {

    @get:Rule val server = MockWebServer()

    private lateinit var client: ApiClient

    @Before
    fun setUp() {
        client = ApiClient("test-key", server.url("/api/sdk/v1").toString().trimEnd('/'))
    }

    /** suspend 버전 [org.junit.Assert.assertThrows] — JUnit4 것은 suspend 블록을 받지 못한다. */
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

    // ① verify — POST 경로·키 헤더·바디, 응답 디코딩
    @Test fun verify_pathHeaderBodyAndDecode() = runTest {
        server.enqueue(MockResponse().setBody("""{ "valid": true, "tenant_code": "t", "positioning_enabled": true }"""))

        val res = client.verify(ReqVerify(platformName = "Android", appId = "com.example"))

        assertTrue(res.valid)
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/api/sdk/v1/auth/verify", req.path)
        assertEquals("test-key", req.getHeader("X-SDK-Key"))
        assertEquals("application/json", req.getHeader("Content-Type"))
        assertEquals("""{"platform_name":"Android","app_id":"com.example"}""", req.body.readUtf8())
    }

    /**
     * ⚠️ 2026-08-21 운영 사고 재현 — 서버 remote_config 에 정수·불리언이 섞여도 초기화가
     * 막히면 안 된다(값은 저장만 하고 읽지 않는다). 최소 응답만 시험하면 이 사고를 못 잡는다.
     */
    @Test fun verify_mixedTypeRemoteConfig_doesNotBreakInitialisation() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """
                {"valid":true,"tenant_code":"onecheck-internal","positioning_enabled":true,
                 "position_rate_hz":4,
                 "remote_config":{"environment":"production","logLevel":"INFO",
                                  "dataCollectionInterval":30,"enableSpatialSensing":true,
                                  "enableAiPrediction":false,"autoCrashReport":true,
                                  "customPayload":"{}"}}
                """.trimIndent(),
            ),
        )

        val res = client.verify(ReqVerify(platformName = "Android", appId = "com.x"))

        assertTrue(res.valid)
        assertTrue(res.positioningEnabled)
        assertEquals("onecheck-internal", res.tenantCode)
        assertEquals(4, res.positionRateHz)

        val cfg = res.remoteConfig!!
        assertEquals("production", cfg["environment"])
        assertEquals("INFO", cfg["logLevel"])
        assertEquals("30", cfg["dataCollectionInterval"])
        assertEquals("true", cfg["enableSpatialSensing"])
        assertEquals("false", cfg["enableAiPrediction"])
    }

    /** 설정 자루가 어떤 모양이든 초기화를 막지 않는다 — 서버가 뭘 넣을지 SDK 는 모른다. */
    @Test fun verify_unreadableRemoteConfig_stillInitialises() = runTest {
        for (weird in listOf(""""문자열"""", "123", "null", """{"nested":{"a":1}}""", """["배열"]""")) {
            server.enqueue(
                MockResponse().setBody("""{"valid":true,"positioning_enabled":true,"remote_config":$weird}"""),
            )
            val res = client.verify(ReqVerify(platformName = "Android", appId = "com.x"))
            assertTrue("remote_config=$weird 때문에 초기화가 막혔다", res.valid)
        }
    }

    /**
     * 디코드가 정말 실패할 때는 무엇을 못 읽었는지 남아야 한다. "decoding" 넉 자만 뜨면
     * 현장에서 원인을 좁힐 수 없다.
     */
    @Test fun decodingFailure_carriesWhatWentWrong() = runTest {
        server.enqueue(MockResponse().setBody("""{"tenant_code":"t"}""")) // valid·positioning_enabled 없음

        val e = assertThrowsSuspend<ApiError.Decoding> {
            client.verify(ReqVerify(platformName = "Android", appId = "com.x"))
        }
        val d = e.detail ?: ""
        assertTrue("어느 응답인지 없다: $d", d.contains("ResVerify"))
        assertTrue("어느 필드인지 없다: $d", d.contains("valid"))
        assertTrue("사람이 읽을 문장이 아니다: ${e.message}", e.message!!.contains("응답 해석 실패"))
    }

    // ② config — GET 경로 (예전 GET /positioning/buildings 는 쓰는 곳이 없어 지웠다 — 감사 SF-C2)
    @Test fun config_isGET() = runTest {
        server.enqueue(MockResponse().setBody("{}"))

        client.config()

        val req = server.takeRequest()
        assertEquals("GET", req.method)
        assertEquals("/api/sdk/v1/config", req.path)
    }

    // ③ 404 → notFound (detail 파싱 포함)
    @Test fun status404_mapsToNotFound() = runTest {
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{ "detail": "no zones" }"""))

        val e = assertThrowsSuspend<ApiError.NotFound> { client.getProfile("p-uuid") }
        assertEquals("no zones", e.detail)
        assertEquals(ApiError.NotFound("no zones"), e)
    }

    // 401 → invalidKey
    @Test fun status401_mapsToInvalidKey() = runTest {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{ "detail": "bad key" }"""))

        val e = assertThrowsSuspend<ApiError.InvalidKey> { client.config() }
        assertEquals(ApiError.InvalidKey("bad key"), e)
    }

    // 403 → forbidden
    @Test fun status403_mapsToForbidden() = runTest {
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{ "detail": "other tenant" }"""))

        val e = assertThrowsSuspend<ApiError.Forbidden> { client.config() }
        assertEquals(ApiError.Forbidden("other tenant"), e)
    }

    // 5xx → server(status, detail)
    @Test fun status500_mapsToServer() = runTest {
        server.enqueue(MockResponse().setResponseCode(500).setBody("""{ "detail": "boom" }"""))

        val e = assertThrowsSuspend<ApiError.Server> { client.config() }
        assertEquals(ApiError.Server(500, "boom"), e)
    }

    // 오류 본문이 {"detail": ...} 형태가 아니면 detail=null (파싱 실패해도 에러 매핑 자체는 유지)
    @Test fun errorBodyNotJsonObject_detailIsNull() = runTest {
        server.enqueue(MockResponse().setResponseCode(500).setBody("이건 JSON 이 아니다"))

        val e = assertThrowsSuspend<ApiError.Server> { client.config() }
        assertNull(e.detail)
    }

    // ④ zone event — 경로 + triggers 디코딩
    @Test fun zoneEvent_pathAndTriggers() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """
                { "accepted": true, "event_id": "e1",
                  "triggers": [ { "trigger_id": "a1", "type": "coupon", "payload": null } ] }
                """.trimIndent(),
            ),
        )

        val res = client.sendZoneEvent(
            ReqZoneEvent(
                profileId = "A", visitorId = "V", floorId = "F", zoneId = "Z",
                status = "IN", occurredAt = "T", platformName = "Android",
            ),
        )

        val req = server.takeRequest()
        assertEquals("/api/sdk/v1/events/zone", req.path)
        assertEquals("coupon", res.triggers.first().type)
    }

    /**
     * payload 에 문자열 아닌 값이 섞여도 존 이벤트가 통째로 날아가면 안 된다 —
     * 깨지면 triggers 가 통째로 사라져 쿠폰·사이니지가 조용히 끊긴다.
     */
    @Test fun zoneEvent_mixedTypePayload_stillDelivers() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """
                { "accepted": true, "event_id": "e1",
                  "triggers": [ { "trigger_id": "a1", "type": "coupon",
                                  "payload": { "title": "10% 할인", "amount": 1000,
                                               "stackable": false, "ratio": 0.1 } } ] }
                """.trimIndent(),
            ),
        )

        val res = client.sendZoneEvent(
            ReqZoneEvent(
                profileId = "A", visitorId = "V", floorId = "F", zoneId = "Z",
                status = "IN", occurredAt = "T", platformName = "Android",
            ),
        )

        assertEquals("payload 때문에 트리거가 통째로 사라졌다", 1, res.triggers.size)
        val payload = res.triggers.first().payload!!
        assertEquals("10% 할인", payload["title"])
        assertEquals("1000", payload["amount"])
        assertEquals("false", payload["stackable"])
    }

    /** payload 가 어떤 모양이든 트리거 자체는 살아 있어야 한다. */
    @Test fun zoneEvent_unreadablePayload_keepsTrigger() = runTest {
        for (weird in listOf("123", """"문자열"""", """{"nested":{"a":1}}""", """["배열"]""")) {
            server.enqueue(
                MockResponse().setBody(
                    """
                    { "accepted": true, "event_id": "e1",
                      "triggers": [ { "trigger_id": "a1", "type": "coupon", "payload": $weird } ] }
                    """.trimIndent(),
                ),
            )
            val res = client.sendZoneEvent(
                ReqZoneEvent(
                    profileId = "A", visitorId = "V", floorId = "F", zoneId = "Z",
                    status = "IN", occurredAt = "T", platformName = "Android",
                ),
            )
            assertEquals("payload=$weird 때문에 트리거가 날아갔다", "coupon", res.triggers.first().type)
        }
    }

    // ⑤ position logs — 경로 + 422 매핑
    @Test fun positionLogs_pathAnd422() = runTest {
        server.enqueue(MockResponse().setResponseCode(422).setBody("""{ "detail": "empty points" }"""))

        val e = assertThrowsSuspend<ApiError.Unprocessable> {
            client.sendPositionLogs(
                ReqPositionBulk(profileId = "A", visitorId = "V", platformName = "Android", points = emptyList()),
            )
        }
        assertEquals(ApiError.Unprocessable("empty points"), e)
        assertEquals("/api/sdk/v1/positioning/logs", server.takeRequest().path)
    }

    // 연결 자체가 실패 → Network (별도 서버를 띄웠다 바로 내려서 재현 — 공용 서버는 건드리지 않는다)
    @Test fun connectionFailure_isNetwork() = runTest {
        val offline = MockWebServer()
        offline.start()
        val badClient = ApiClient("test-key", offline.url("/api/sdk/v1").toString().trimEnd('/'))
        offline.shutdown()

        val e = assertThrowsSuspend<ApiError.Network> { badClient.config() }
        assertEquals(SdkErrorCode.NETWORK, e.code)
    }

    // code 매핑 — iOS SdkErrorCode.swift 243-253행과 동일해야 한다.
    @Test fun codeMapsEveryApiErrorSubtypeToSdkErrorCode() {
        assertEquals(SdkErrorCode.INVALID_KEY, ApiError.InvalidKey(null).code)
        assertEquals(SdkErrorCode.FORBIDDEN, ApiError.Forbidden(null).code)
        assertEquals(SdkErrorCode.UNPROCESSABLE, ApiError.NotFound(null).code)
        assertEquals(SdkErrorCode.UNPROCESSABLE, ApiError.Unprocessable(null).code)
        assertEquals(SdkErrorCode.SERVER, ApiError.Server(500, null).code)
        assertEquals(SdkErrorCode.NETWORK, ApiError.Network(java.io.IOException()).code)
        assertEquals(SdkErrorCode.DECODING, ApiError.Decoding(null).code)
    }

    // equals — 같은 하위 타입 + 같은 필드일 때만 같다 (iOS Equatable).
    @Test fun equalsIsBySubtypeAndFields() {
        assertEquals(ApiError.NotFound("x"), ApiError.NotFound("x"))
        assertEquals(ApiError.NotFound(null), ApiError.NotFound(null))
        assertNotEquals(ApiError.NotFound("x"), ApiError.NotFound("y"))
        assertNotEquals(ApiError.NotFound("x"), ApiError.Unprocessable("x")) // 같은 필드, 다른 하위 타입
        assertEquals(ApiError.Server(500, "x"), ApiError.Server(500, "x"))
        assertNotEquals(ApiError.Server(500, "x"), ApiError.Server(501, "x"))
    }

    // ⑥ createProfile — POST /profiles, attrs 그대로 전송
    @Test fun createProfile_postsAttributesAndDecodesId() = runTest {
        server.enqueue(MockResponse().setBody("""{ "profile_id": "p-1" }"""))

        val res = client.createProfile(mapOf("gender" to "F"))

        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/api/sdk/v1/profiles", req.path)
        assertEquals("""{"attributes":{"gender":"F"}}""", req.body.readUtf8())
        assertEquals("p-1", res.profileId)
    }

    // ⑦ getProfile — GET /profiles/{id}
    @Test fun getProfile_isGETWithId() = runTest {
        server.enqueue(MockResponse().setBody("""{ "profile_id": "p-1", "attributes": {"gender": "F"} }"""))

        val res = client.getProfile("p-1")

        val req = server.takeRequest()
        assertEquals("GET", req.method)
        assertEquals("/api/sdk/v1/profiles/p-1", req.path)
        assertEquals("F", res.attributes?.get("gender"))
    }

    // ⑧ putProfile — PUT /profiles/{id}, 속성 전체 교체
    @Test fun putProfile_isPUTWithBody() = runTest {
        server.enqueue(MockResponse().setBody("""{ "profile_id": "p-1", "attributes": {"gender": "M"} }"""))

        val res = client.putProfile("p-1", mapOf("gender" to "M"))

        val req = server.takeRequest()
        assertEquals("PUT", req.method)
        assertEquals("/api/sdk/v1/profiles/p-1", req.path)
        assertEquals("""{"attributes":{"gender":"M"}}""", req.body.readUtf8())
        assertEquals("M", res.attributes?.get("gender"))
    }

    // ⑨ deleteProfile — DELETE, 본문 없음이지만 Content-Type 헤더는 붙는다
    @Test fun deleteProfile_isDELETEWithNoBodyButContentTypeHeader() = runTest {
        server.enqueue(MockResponse().setBody("""{ "deleted": true }"""))

        val res = client.deleteProfile("p-1")

        val req = server.takeRequest()
        assertEquals("DELETE", req.method)
        assertEquals("/api/sdk/v1/profiles/p-1", req.path)
        assertEquals("application/json", req.getHeader("Content-Type"))
        assertEquals(0L, req.bodySize)
        assertTrue(res.deleted)
    }

    // ⑩ sendLogs — POST /logs
    @Test fun sendLogs_postsEntriesAndDecodesAcceptedCount() = runTest {
        server.enqueue(MockResponse().setBody("""{ "accepted_count": 1 }"""))

        val res = client.sendLogs(
            ReqSdkLogs(
                profileId = "A",
                platformName = "Android",
                sdkVersion = "0.0.1",
                entries = listOf(SdkLogEntry(code = "E5001", level = "ERROR", message = "네트워크 실패", at = "t")),
            ),
        )

        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/api/sdk/v1/logs", req.path)
        assertEquals(1, res.acceptedCount)
    }

    // 모든 요청 공통 — baseUrl 뒤에 그대로 붙고, 키·타입 헤더는 GET 에도 실린다
    @Test fun everyRequestCarriesSdkKeyAndContentTypeHeaders() = runTest {
        server.enqueue(MockResponse().setBody("{}"))

        client.config()

        val req = server.takeRequest()
        assertEquals("test-key", req.getHeader("X-SDK-Key"))
        assertEquals("application/json", req.getHeader("Content-Type"))
    }
}
