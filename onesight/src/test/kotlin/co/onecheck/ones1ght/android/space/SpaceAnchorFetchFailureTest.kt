package co.onecheck.ones1ght.android.space

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * **로케이터를 못 받아도 층은 열린다.**
 *
 * 예전에는 앵커 조회가 던졌고, `loadFloorState` 가 그걸 그대로 위로 올렸다. 그 바람에 조회가
 * 한 번 실패하면 앱에 도면도 존도 격자도 안 그려지고 "지도 조회 실패" 만 떴다.
 *
 * 도면·존은 앵커와 다른 경로로 받아 오고 이미 손에 있다. 로케이터가 없다고 지도를 통째로
 * 지울 이유가 없다. 못 받으면 측위만 못 하면 된다. 포팅 원본: SpaceAnchorFetchFailureTests.swift.
 */
class SpaceAnchorFetchFailureTest {

    @get:Rule val server = MockWebServer()

    private fun client(): SpaceServiceClient = SpaceServiceClient(
        sdkKey = "ock_sdk_x",
        spaceKey = "gsk_x",
        http = OkHttpClient(),
        consoleBase = server.url("/api/sdk/v1").toString().trimEnd('/'),
        spaceHost = server.url("/").toString(),
    )

    private val png1x1 =
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=="

    /** 앵커만 실패하고 도면·존은 멀쩡할 때. */
    private fun stubAnchorsFailing(status: Int) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path ?: ""
                return when {
                    path.endsWith("/anchors") -> MockResponse().setResponseCode(status) // ← 여기만 깨진다
                    path.endsWith("/plan") -> MockResponse().setBody(
                        """
                        {"has_plan":true,"floor_name":"607호","plan":{"image":
                          {"data_url":"data:image/png;base64,$png1x1","width_m":10.0,
                           "img_w":100,"img_h":50,"origin_x":0.0,"origin_y":0.0}}}
                        """.trimIndent(),
                    )
                    path.contains("zone") -> MockResponse().setBody(
                        """
                        {"zones":[{"zone_id":"z-1","name":"A","is_active":true,
                          "polygon":[[0.0,0.0],[5.0,0.0],[5.0,4.0],[0.0,4.0]]}]}
                        """.trimIndent(),
                    )
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
    }

    /** 연결 자체가 끊겨도(IOException) 마찬가지다. */
    private fun stubAnchorsDisconnecting() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path ?: ""
                return when {
                    path.endsWith("/anchors") -> MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START)
                    path.endsWith("/plan") -> MockResponse().setBody(
                        """
                        {"has_plan":true,"floor_name":"607호","plan":{"image":
                          {"data_url":"data:image/png;base64,$png1x1","width_m":10.0,
                           "img_w":100,"img_h":50,"origin_x":0.0,"origin_y":0.0}}}
                        """.trimIndent(),
                    )
                    path.contains("zone") -> MockResponse().setBody(
                        """{"zones":[{"zone_id":"z-1","name":"A","is_active":true,"polygon":[[0.0,0.0],[5.0,0.0],[5.0,4.0],[0.0,4.0]]}]}""",
                    )
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
    }

    /** ★ 핵심 — 앵커가 5xx 로 죽어도 도면과 존은 그대로 온다. */
    @Test fun floorStillLoadsWhenAnchorFetchFails() = runTest {
        stubAnchorsFailing(503)

        val state = client().loadFloorState(buildingId = "b-1", floorId = "14")

        assertTrue("앵커가 죽었다고 도면까지 잃었다", state.hasPlan)
        assertEquals("앵커가 죽었다고 존까지 잃었다", 1, state.zones.size)
        assertTrue(state.locators.isEmpty())
        assertNull(state.sessionId)
        assertTrue("못 받은 것을 못 받았다고 표시하지 않았다", state.locatorsFetchFailed)
    }

    /** 404 도 마찬가지다 — 상류가 그 층의 앵커를 모른다는 것뿐, 지도를 지울 이유가 아니다. */
    @Test fun floorStillLoadsWhenAnchorsAre404() = runTest {
        stubAnchorsFailing(404)

        val state = client().loadFloorState(buildingId = "b-1", floorId = "14")

        assertTrue(state.hasPlan)
        assertEquals(1, state.zones.size)
        assertTrue(state.locatorsFetchFailed)
    }

    /** 연결이 실패(IOException)해도 똑같이 성공해야 한다 — 상태코드가 아니라 전송 자체가 죽는 경우. */
    @Test fun floorStillLoadsWhenAnchorConnectionFails() = runTest {
        stubAnchorsDisconnecting()

        val state = client().loadFloorState(buildingId = "b-1", floorId = "14")

        assertTrue(state.hasPlan)
        assertEquals(1, state.zones.size)
        assertTrue(state.locators.isEmpty())
        assertNull(state.sessionId)
        assertTrue(state.locatorsFetchFailed)
    }

    /**
     * "못 받았다" 와 "층에 없다" 는 다르다. 빈 배열이 정상 응답으로 오면
     * `locatorsFetchFailed` 는 **거짓**이어야 한다.
     */
    @Test fun anEmptyAnchorListIsNotAFetchFailure() = runTest {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path ?: ""
                return when {
                    path.endsWith("/anchors") -> MockResponse().setBody("""{"anchors":[]}""")
                    path.endsWith("/plan") -> MockResponse().setBody(
                        """
                        {"has_plan":true,"floor_name":"607호","plan":{"image":
                          {"data_url":"data:image/png;base64,$png1x1","width_m":10.0,
                           "img_w":100,"img_h":50,"origin_x":0.0,"origin_y":0.0}}}
                        """.trimIndent(),
                    )
                    path.contains("zone") -> MockResponse().setBody("""{"zones":[]}""")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        val state = client().loadFloorState(buildingId = "b-1", floorId = "14")

        assertTrue(state.locators.isEmpty())
        assertFalse("빈 목록을 조회 실패로 오인했다", state.locatorsFetchFailed)
    }

    /**
     * `loadLocators` 도 던지지 않는다 — 앱이 층을 여는 두 번째 관문이다. Kotlin 인터페이스는
     * iOS 와 달리 실패를 빈 [co.onecheck.ones1ght.android.model.FloorLocators] 가 아니라
     * null 로 돌려준다(task-5-brief 시그니처).
     */
    @Test fun loadLocatorsDoesNotThrowOnFailure() = runTest {
        stubAnchorsFailing(500)

        val got = client().loadLocators("14")

        assertNull("측위 불가로 떨어져야 한다", got)
    }
}
