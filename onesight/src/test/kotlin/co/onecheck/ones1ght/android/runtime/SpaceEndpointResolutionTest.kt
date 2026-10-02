package co.onecheck.ones1ght.android.runtime

import co.onecheck.ones1ght.android.identity.IdentityStore
import co.onecheck.ones1ght.android.model.ConfigChange
import co.onecheck.ones1ght.android.model.ResSdkConfig
import co.onecheck.ones1ght.android.network.ApiClient
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.net.InetAddress
import java.net.UnknownHostException

/**
 * 공간 조회가 어디로 가는가 · 도면 캐시 — 감사 SF-A9(=iOS S15) · SF-A11 · SF-A15(=iOS S16) · SF-A10.
 *
 * 코디네이터의 **기본** 공간 클라이언트(운영과 같은 조립)를 쓴다. 바깥 호스트로는 나가지 못하게 DNS 를 막아,
 * 고치기 전 코드가 실제 서버로 새도 요청이 나가지 않고 실패로만 드러나게 했다.
 */
class SpaceEndpointResolutionTest {

    @get:Rule val server = MockWebServer()

    private val routes = Routes()

    @Before fun setUp() {
        server.dispatcher = routes
    }

    /** 이 서버(루프백)만 풀리는 DNS — 운영 호스트로 새는 요청은 UnknownHostException 으로 막힌다. */
    private val localOnly = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> =
            if (hostname == server.hostName || hostname == "localhost") Dns.SYSTEM.lookup(hostname) else throw UnknownHostException(hostname)
    }

    private fun TestScope.coordinator(): SessionCoordinator {
        val http = OkHttpClient.Builder().dns(localOnly).retryOnConnectionFailure(false).build()
        val api = ApiClient.create("ock_sdk_x", server.url("/api/sdk/v1").toString().trimEnd('/'), http)
        return SessionCoordinator(
            api = api,
            identity = IdentityStore.create(InMemoryKeyValueStore(), today = { "20260928" }),
            appId = "co.onecheck.test",
            scope = backgroundScope,
            lifecycle = null,
            clock = { BASE_MS + testScheduler.currentTime },
        )
    }

    private fun stub(config: String) {
        routes.handler = { path ->
            when {
                path.endsWith("/auth/verify") -> json(VERIFY_OK)
                path.endsWith("/config") -> json(config)
                path.endsWith("/plan") -> json(
                    """{"has_plan":true,"floor_name":"1F","plan":{"image":
                       {"data_url":"data:image/png;base64,iVBORw0KGgo=","width_m":10.0,
                        "img_w":100,"img_h":50,"origin_x":0.0,"origin_y":0.0}}}""",
                )
                path.endsWith("/anchors") -> json("""{"anchors":[{"uwbMac":"AA:BB:0B:4B","x":1.0,"y":2.0,"sessionId":7}]}""")
                path.endsWith("/zones") -> json("""{"zones":[]}""")
                path.endsWith("/floors") -> json("""{"floors":[{"floor_id":"14","name":"1F","has_plan":true}]}""")
                else -> json("{}")
            }
        }
    }

    private val geoHere: String get() = server.url("/").toString()

    /** SF-A9 — initialize(baseUrl) 의 서버로 공간 조회(층 목록)가 간다. */
    @Test fun consoleSpaceLookupsUseApiBaseUrl() = runTest {
        stub("""{ "geo_sdk_key": "gsk_x", "geo_base_url": "$geoHere" }""")
        val c = coordinator()
        c.prepare()
        assertEquals(listOf("14"), c.floors("b-1").map { it.id })
        assertTrue(routes.paths().toString(), routes.paths().contains("/api/sdk/v1/positioning/buildings/b-1/floors"))
    }

    /** SF-A11 — 콘솔이 준 geo_base_url 로 앵커를 받는다(호스트 하드코딩이면 이 서버에 앵커 요청이 없다). */
    @Test fun anchorsUseConsoleGeoBaseUrl() = runTest {
        stub("""{ "geo_sdk_key": "gsk_x", "geo_base_url": "$geoHere" }""")
        val c = coordinator()
        c.prepare()
        val locators = c.locators("b-1", "14")
        assertEquals(1, locators.locators.size)
        assertTrue(routes.paths().toString(), routes.paths().contains("/api/m/floors/14/anchors"))
    }

    /** geo_base_url 이 없거나 https(루프백 제외 http)가 아니면 기본 호스트를 쓴다 — 키를 평문으로 보내지 않는다. */
    @Test fun invalidGeoBaseUrlFallsBackToDefaultHost() {
        val s = co.onecheck.ones1ght.android.space.SpaceServiceClient
        assertEquals(s.SPACE_HOST, s.spaceHostFor(null))
        assertEquals(s.SPACE_HOST, s.spaceHostFor(""))
        assertEquals(s.SPACE_HOST, s.spaceHostFor("not a url"))
        assertEquals(s.SPACE_HOST, s.spaceHostFor("http://space.example.com"))
        assertEquals("https://space.example.com/", s.spaceHostFor("https://space.example.com"))
        assertEquals("http://127.0.0.1:8080/", s.spaceHostFor("http://127.0.0.1:8080"))
    }

    /** SF-A15 · iOS S16 — plan.changed 를 받으면 그 층 도면 캐시를 버린다(예전엔 프로세스 끝까지 옛 도면). */
    @Test fun planChangedEvictsPlanCache() = runTest {
        stub("""{ "geo_sdk_key": "gsk_x", "geo_base_url": "$geoHere" }""")
        val c = coordinator()
        c.prepare()
        c.floor("b-1", "14")
        c.floor("b-1", "14")
        assertEquals("캐시가 두 번째 조회를 막아야 한다", 1, routes.count("/plan"))
        c.deliverConfigChange(ConfigChange.PlanChanged(floorId = "14"))
        c.floor("b-1", "14")
        assertEquals(2, routes.count("/plan"))
    }

    /** 층을 모르는 plan.changed(floor_id 없음)는 도면 캐시를 전부 버린다. */
    @Test fun planChangedWithoutFloorEvictsAll() = runTest {
        stub("""{ "geo_sdk_key": "gsk_x", "geo_base_url": "$geoHere" }""")
        val c = coordinator()
        c.prepare()
        c.floor("b-1", "14")
        c.deliverConfigChange(ConfigChange.PlanChanged(floorId = null))
        c.floor("b-1", "14")
        assertEquals(2, routes.count("/plan"))
    }

    /** 신호를 놓쳐도 도면이 영원히 낡지 않게 — 캐시는 일정 시간 뒤 다시 받는다. */
    @Test fun planCacheExpires() = runTest {
        stub("""{ "geo_sdk_key": "gsk_x", "geo_base_url": "$geoHere" }""")
        val c = coordinator()
        c.prepare()
        c.floor("b-1", "14")
        testScheduler.advanceTimeBy(co.onecheck.ones1ght.android.space.SpaceServiceClient.PLAN_TTL_MS + 1)
        c.floor("b-1", "14")
        assertEquals(2, routes.count("/plan"))
    }

    /**
     * SF-A10 — 콘솔 /config 의 쓰기 권한 파트너 키를 SDK 가 읽지도 들고 있지도 않는다(DTO 필드 자체가 없다).
     * 서버가 아직 내려줘도 무시된다.
     */
    @Test fun partnerKeyIsNeitherDecodedNorKept() = runTest {
        stub("""{ "geo_sdk_key": "gsk_x", "geo_partner_key": "gpk_secret" }""")
        val c = coordinator()
        c.prepare()
        val names = (ResSdkConfig::class.java.declaredFields + SessionCoordinator::class.java.declaredFields).map { it.name }
        assertFalse(names.toString(), names.any { it.contains("partner", ignoreCase = true) || it == "spaceServiceKey" })
    }
}
