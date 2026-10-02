package co.onecheck.ones1ght.android.network

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * 콘솔이 내려주는 관련 키 — 앱이 키를 심어 나르지 않게 하는 자리. 포팅 원본: SdkConfigFetchTests.swift.
 */
class SdkConfigFetchTest {

    @get:Rule val server = MockWebServer()

    private lateinit var client: ApiClient

    @Before
    fun setUp() {
        client = ApiClient("ock_sdk_x", server.url("/api/sdk/v1").toString().trimEnd('/'))
    }

    @Test fun decodesEveryKey() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """
                { "tenant_code": "acme",
                  "google_map_key": "AIzaSyExample",
                  "geo_sdk_key": "gsk_example",
                  "geo_partner_key": "gpk_example",
                  "geo_base_url": "https://geospace.geoplan.io" }
                """.trimIndent(),
            ),
        )

        val cfg = client.config()

        assertEquals("acme", cfg.tenantCode)
        assertEquals("AIzaSyExample", cfg.googleMapKey)
        assertEquals("gsk_example", cfg.geoSdkKey)
        assertEquals("https://geospace.geoplan.io", cfg.geoBaseUrl)
    }

    /**
     * 서버가 부분 실패로 null 을 내려도 디코드가 깨지면 안 된다 — 깨지면 키 하나 때문에
     * 초기화가 통째로 실패한다(2026-08-21 과 같은 모양).
     */
    @Test fun nullKeysDecodeAsNull() = runTest {
        server.enqueue(MockResponse().setBody("""{ "tenant_code": "acme", "google_map_key": null }"""))

        val cfg = client.config()

        assertEquals("acme", cfg.tenantCode)
        assertNull(cfg.googleMapKey)
        assertNull("빠진 필드도 null 이어야 한다", cfg.geoSdkKey)
    }

    /** 서버가 필드를 늘려도 디코드가 깨지지 않아야 한다. */
    @Test fun unknownFieldsAreIgnored() = runTest {
        server.enqueue(MockResponse().setBody("""{ "tenant_code": "acme", "something_new": 42 }"""))

        val cfg = client.config()

        assertEquals("acme", cfg.tenantCode)
    }

    // GET 경로 + X-SDK-Key 헤더 — verify()의 pathHeaderBodyAndDecode 시험과 같은 확인을 config()에도.
    @Test fun config_pathMethodAndSdkKeyHeader() = runTest {
        server.enqueue(MockResponse().setBody("""{ "tenant_code": "acme" }"""))

        client.config()

        val req = server.takeRequest()
        assertEquals("ock_sdk_x", req.getHeader("X-SDK-Key"))
        assertEquals("/api/sdk/v1/config", req.path)
        assertEquals("GET", req.method)
    }
}
