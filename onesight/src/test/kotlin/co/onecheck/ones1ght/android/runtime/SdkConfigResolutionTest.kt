package co.onecheck.ones1ght.android.runtime

//
//  SdkConfigResolutionTest.kt
//  측위 키의 출처는 콘솔 하나뿐이다 — 앱이 넘기는 경로가 없으므로 폴백도 없다.
//  포팅 원본: SdkConfigResolutionTests.swift.
//

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class SdkConfigResolutionTest {

    @get:Rule val server = MockWebServer()

    private val routes = Routes()

    @Before
    fun setUp() {
        server.dispatcher = routes
    }

    /** verify 는 늘 통과시키고, /config 응답만 테스트가 정한다. */
    private fun stub(configStatus: Int, configBody: String) {
        routes.handler = { path ->
            when {
                path.endsWith("/auth/verify") -> json(VERIFY_OK)
                path.endsWith("/config") -> json(configBody, configStatus)
                else -> json("{}")
            }
        }
    }

    private suspend fun TestScope.prepared(
        spaceClients: MutableList<Pair<String, String>>? = null,
    ): Pair<SessionCoordinator, List<String>> {
        val c = makeCoordinator(server, apiKey = "ock_sdk_x", spaceClients = spaceClients)
        val lines = mutableListOf<String>()
        c.onLog = { _, line -> lines.add(line) }
        c.prepare()
        return c to lines
    }

    /** 기본 경로 — 앱은 SDK 키 하나만 넘기고 측위 키는 콘솔에서 온다. */
    @Test fun consoleSuppliesTheLicense() = runTest {
        stub(200, """{ "geo_sdk_key": "gsk_console" }""")

        val (c, _) = prepared()

        assertEquals("gsk_console", c.positioningLicense)
        assertTrue(c.isPrepared)
        assertFalse(c.keyResolutionFailed)
    }

    /** ⚠️ 어떤 로그에도 키 값이 실리면 안 된다. */
    @Test fun keysNeverAppearInLogs() = runTest {
        stub(200, """{ "geo_sdk_key": "gsk_console", "google_map_key": "AIza1", "geo_partner_key": "gpk_1" }""")

        val (_, lines) = prepared()

        assertFalse(
            "키가 로그에 샜다: $lines",
            lines.any { it.contains("gsk_console") || it.contains("AIza1") || it.contains("gpk_1") },
        )
    }

    /** 서버가 잠깐 흔들려도 초기화 자체는 성공해야 한다. 대신 재시도 표시를 남긴다. */
    @Test fun configFailureDoesNotBlockInit() = runTest {
        stub(500, """{ "detail": "boom" }""")

        val (c, lines) = prepared()

        assertTrue("초기화 자체는 성공해야 한다", c.isPrepared)
        assertNull(c.positioningLicense)
        assertTrue("통신 실패는 재시도로 풀릴 수 있다", c.keyResolutionFailed)
        assertTrue(lines.toString(), lines.any { it.startsWith("[E1007]") && it.endsWith("reason=config_failed") })
    }

    /** 응답은 왔는데 값이 비어 있으면 재시도해도 소용없다 — 콘솔 설정 문제다. */
    @Test fun missingKeyIsNotMarkedForRetry() = runTest {
        stub(200, """{ "geo_sdk_key": null }""")

        val (c, _) = prepared()

        assertNull(c.positioningLicense)
        assertTrue(c.isPrepared)
        assertFalse("설정 부재는 재시도 대상이 아니다", c.keyResolutionFailed)
    }

    /** 빈 문자열도 "없음"이다 — 안 그러면 빈 라이선스가 엔진까지 내려간다. */
    @Test fun emptyKeyCountsAsMissing() = runTest {
        stub(200, """{ "geo_sdk_key": "" }""")

        val (c, _) = prepared()

        assertNull(c.positioningLicense)
        assertNull(c.spaceClient)
    }

    @Test fun otherKeysAreKept() = runTest {
        stub(
            200,
            """{ "google_map_key": "AIza1", "geo_partner_key": "gpk_1", "geo_base_url": "https://space.example" }""",
        )

        val (c, _) = prepared()

        assertEquals("AIza1", c.googleMapKey)
        assertEquals("https://space.example", c.spaceServiceBaseUrl)
    }

    // MARK: - I2: 경고가 report() 채널(콘솔 로그 분석기)에도 남는가

    @Test fun keyUnavailableReachesReportChannelWhenConsoleHasNone() = runTest {
        stub(200, """{ "geo_sdk_key": null }""")

        val (_, lines) = prepared()

        assertTrue(lines.toString(), lines.any { it.startsWith("[${SdkErrorCode.KEY_UNAVAILABLE.code}]") })
    }

    @Test fun keyUnavailableReachesReportChannelWhenConfigFails() = runTest {
        stub(500, """{ "detail": "boom" }""")

        val (_, lines) = prepared()

        assertTrue(lines.toString(), lines.any { it.startsWith("[${SdkErrorCode.KEY_UNAVAILABLE.code}]") })
    }

    /** 원인은 로그에서 구분돼야 한다 — 통신 실패와 미설정은 대응이 다르다(재시도 vs 콘솔 설정). */
    @Test fun unavailableReasonDistinguishesCause() = runTest {
        stub(500, """{ "detail": "boom" }""")
        val (_, failLines) = prepared()

        routes.reset()
        stub(200, """{ "geo_sdk_key": null }""")
        val (_, emptyLines) = prepared()

        assertTrue(failLines.toString(), failLines.any { it.contains("reason=config_failed") })
        assertTrue(emptyLines.toString(), emptyLines.any { it.contains("reason=console_no_key") })
    }

    /** 경고 문구도 onLog 에 ERROR 로 남는다(report 줄과 별개). */
    @Test fun keyUnavailableAlsoLogsLocalizedErrorLine() = runTest {
        stub(200, """{ "geo_sdk_key": null }""")
        val c = makeCoordinator(server)
        val levels = mutableListOf<Pair<LogLevel, String>>()
        c.onLog = { level, line -> levels.add(level to line) }
        c.prepare()
        // 코드 줄 하나에 번역 문구가 실린다 — 같은 사건이 두 줄로 찍히지 않는다(감사 K9).
        val e1007 = levels.filter { it.second.contains("[E1007]") }
        assertEquals(1, e1007.size)
        assertEquals(LogLevel.ERROR, e1007.single().first)
        assertTrue(e1007.single().second.startsWith("[E1007] " + SdkLocalized.t("coord.keyUnavailable")))
        assertTrue(levels.none { it.second == SdkLocalized.t("coord.keyUnavailable") })
    }

    // MARK: - I3: 콘솔 키로 만든 SpaceServiceClient 가 주입된 경로를 타는가

    @Test fun createdSpaceServiceClientUsesInjectedFactory() = runTest {
        routes.handler = { path ->
            when {
                path.endsWith("/auth/verify") -> json(VERIFY_OK)
                path.endsWith("/config") -> json("""{ "geo_sdk_key": "gsk_console" }""")
                path.endsWith("/positioning/buildings") ->
                    json("""{ "buildings": [ { "building_id": "B1", "name": "Test" } ] }""")
                else -> json("{}")
            }
        }
        val made = mutableListOf<Pair<String, String>>()
        val (c, _) = prepared(spaceClients = made)

        val buildings = c.buildings()

        assertEquals("SDK 키 + 콘솔이 준 공간 서비스 키로 만든다", listOf("ock_sdk_x" to "gsk_console"), made)
        assertEquals(listOf("B1"), buildings.map { it.id })
        assertTrue(
            "만들어진 SpaceServiceClient 가 스텁을 타지 않았다",
            routes.requests.any { it.path.endsWith("/positioning/buildings") },
        )
    }

    /** 키를 못 구했으면 공간 조회는 빈 값(목록)·notInitialized(단건)로 떨어진다. */
    @Test fun withoutKeySpaceQueriesFallBack() = runTest {
        stub(200, """{ "geo_sdk_key": null }""")
        val (c, _) = prepared()
        assertEquals(emptyList<Any>(), c.buildings())
        assertEquals(emptyList<Any>(), c.floors("B"))
        assertEquals(emptyList<Any>(), c.zones("B", "F"))
        try {
            c.locators("F")
            org.junit.Assert.fail("notInitialized여야 함")
        } catch (e: co.onecheck.ones1ght.android.SdkError.NotInitialized) {
            // 기대한 경로
        }
    }

    // MARK: - begin() 의 순단 회복 (retryKeyResolutionIfNeeded)

    @Test fun retryResolvesAfterTransientFailure() = runTest {
        stub(500, """{ "detail": "boom" }""")
        val (c, _) = prepared()
        assertTrue(c.keyResolutionFailed)

        stub(200, """{ "geo_sdk_key": "gsk_console" }""")
        c.retryKeyResolutionIfNeeded()

        assertEquals("gsk_console", c.positioningLicense)
        assertFalse(c.keyResolutionFailed)
        assertEquals("verify 는 다시 태우지 않는다", 1, routes.count("/auth/verify"))
    }

    @Test fun retryIsNoOpWhenNotFailed() = runTest {
        stub(200, """{ "geo_sdk_key": null }""")
        val (c, _) = prepared()
        val before = routes.count("/config")
        c.retryKeyResolutionIfNeeded()
        assertEquals(before, routes.count("/config"))
    }
}
