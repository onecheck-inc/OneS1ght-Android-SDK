package co.onecheck.ones1ght.android.space

import co.onecheck.ones1ght.android.network.ApiError
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * 감사 SF-C7 — 공간 조회가 폴백·빈 값으로 삼키던 실패 중 **키 문제(401·403)** 를 드러낸다. 동작(폴백 결과)은 그대로다.
 */
class SpaceAuthFailureTest {

    @get:Rule val server = MockWebServer()

    private val failures = mutableListOf<Pair<ApiError, String>>()

    private fun client(): SpaceServiceClient = SpaceServiceClient(
        sdkKey = "ock_sdk_x",
        spaceKey = "gsk_x",
        http = OkHttpClient(),
        consoleBase = server.url("/api/sdk/v1").toString().trimEnd('/'),
        spaceHost = server.url("/").toString(),
    ).also { c -> c.onAuthFailure = { e, ctx -> failures += e to ctx } }

    private fun serve(consoleBuildings: MockResponse, anchors: MockResponse = MockResponse().setBody("""{"anchors":[]}""")) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path ?: ""
                return when {
                    path.endsWith("/api/sdk/v1/positioning/buildings") -> consoleBuildings
                    path.endsWith("/api/m/buildings") ->
                        MockResponse().setBody("""{"buildings":[{"buildingId":"b-s","buildingName":"폴백","floors":[]}]}""")
                    path.endsWith("/anchors") -> anchors
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
    }

    /** 콘솔 401 — 폴백 결과는 그대로 오고, 키 문제는 한 번만 알린다(폴링이 덮지 않게). */
    @Test fun consoleKeyRevokedIsReportedOnceAndFallbackStays() = runTest {
        serve(consoleBuildings = MockResponse().setResponseCode(401).setBody("""{"detail":"revoked"}"""))
        val c = client()
        assertEquals(listOf("b-s"), c.buildings().map { it.id })
        assertEquals(listOf("b-s"), c.buildings().map { it.id })
        assertEquals(1, failures.size)
        assertEquals(ApiError.InvalidKey("revoked"), failures.single().first)
        assertTrue(failures.single().second, failures.single().second.startsWith("console buildings"))
    }

    /** 공간 서비스 키 403 — 앵커는 못 받은 것(null)으로 남고, 사실을 알린다. */
    @Test fun spaceKeyForbiddenOnAnchorsIsReported() = runTest {
        serve(consoleBuildings = MockResponse().setBody("""{"buildings":[]}"""), anchors = MockResponse().setResponseCode(403))
        assertEquals(null, client().loadLocators("14"))
        assertEquals(listOf("E5004"), failures.map { it.first.code.code })
    }

    /** 키 문제가 아닌 실패(404·500)는 종전대로 조용히 폴백한다 — 소음을 늘리지 않는다. */
    @Test fun otherFailuresStaySilent() = runTest {
        serve(consoleBuildings = MockResponse().setResponseCode(500))
        assertEquals(listOf("b-s"), client().buildings().map { it.id })
        assertTrue(failures.isEmpty())
    }
}
