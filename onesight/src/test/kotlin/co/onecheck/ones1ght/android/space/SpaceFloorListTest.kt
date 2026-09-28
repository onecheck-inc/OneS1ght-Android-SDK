package co.onecheck.ones1ght.android.space

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * 층 목록은 **도면을 받지 않는다.**
 *
 * 예전에는 이름을 얻으려고 층마다 plan 을 불렀다. plan 응답은 prod 실측 888KB 라, 층이 N개면
 * 드롭다운이 뜨기 전에 888KB × N 을 순서대로 받았다 — 실기기에서 "빌딩을 고르면 층이 한참
 * 뒤에 뜬다"로 나타났다.
 *
 * 이 테스트가 지키는 것은 **요청의 개수**다. 이름을 잘 채우는지만 보면, 누군가 다시 plan 을
 * 부르도록 되돌려도 통과해 버린다. 포팅 원본: SpaceFloorListTests.swift.
 */
class SpaceFloorListTest {

    @get:Rule val server = MockWebServer()

    private fun client(): SpaceServiceClient = SpaceServiceClient(
        sdkKey = "ock_sdk_x",
        spaceKey = "gsk_x",
        http = OkHttpClient(),
        consoleBase = server.url("/api/sdk/v1").toString().trimEnd('/'),
        spaceHost = server.url("/").toString(),
    )

    @Test fun floorListMakesExactlyOneRequestAndFillsNames() = runTest {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                // plan 을 부르면 여기 걸린다 — 아래 요청 수 단언이 잡는다.
                if (request.path?.endsWith("/floors") != true) return MockResponse().setResponseCode(500)
                val body = """
                    {"building_id":"b-1","floors":[
                      {"floor_id":"14","name":"607호","has_plan":true},
                      {"floor_id":"15","name":"304로","has_plan":false}
                    ]}
                """.trimIndent()
                return MockResponse().setBody(body)
            }
        }

        val floors = client().floors("b-1")

        assertEquals(listOf("14", "15"), floors.map { it.id })
        assertEquals(listOf("607호", "304로"), floors.map { it.name })
        assertEquals(listOf(true, false), floors.map { it.hasPlan })
        // 핵심 — 층이 2개여도 왕복은 1회다.
        assertEquals(1, server.requestCount)
        assertTrue(server.takeRequest().path!!.endsWith("/floors"))
    }

    /** 콘솔 배포 시차 — name·has_plan 이 아직 없던 응답에도 깨지지 않아야 한다. */
    @Test fun floorListSurvivesResponseWithoutNameOrHasPlan() = runTest {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse().setBody("""{"building_id":"b-1","floors":[{"floor_id":"14"}]}""")
        }

        val floors = client().floors("b-1")

        assertEquals(1, floors.size)
        assertEquals("14", floors[0].id)
        assertFalse(floors[0].hasPlan)
        assertEquals(1, server.requestCount)
    }
}
