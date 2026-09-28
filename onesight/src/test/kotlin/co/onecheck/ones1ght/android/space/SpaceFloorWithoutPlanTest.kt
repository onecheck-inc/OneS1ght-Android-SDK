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
 * **도면이 없는 층은 오류가 아니다.**
 *
 * 매장은 도면을 올리지만 산업 현장처럼 올릴 도면 자체가 없는 곳이 있다. 좌표는 도면이
 * 아니라 로케이터 배치에서 나온다. 그러므로 도면이 없어도 측위는 정상이어야 하고, 이
 * 테스트가 지키는 것은 "도면만 빠지고 나머지는 다 온다" 이다. 포팅 원본:
 * SpaceFloorWithoutPlanTests.swift.
 */
class SpaceFloorWithoutPlanTest {

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

    /** 콘솔이 도면 없음을 주는 층 — 앵커·세션·존은 그대로 와야 한다. */
    @Test fun floorWithoutPlanStillLoadsLocatorsSessionAndZones() = runTest {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path ?: ""
                return when {
                    path.endsWith("/plan") -> MockResponse()
                        // 콘솔 프록시가 "도면 없음" 을 명시한다.
                        .setBody("""{"has_plan":false,"floor_name":"3공장 2층","plan":null}""")
                    path.endsWith("/anchors") -> MockResponse().setBody(
                        """
                        {"anchors":[
                          {"uwbMac":"AABBCCDD9DD7","x":1.0,"y":2.0,"sessionId":4444,"clusterStatus":"auto_done"},
                          {"uwbMac":"AABBCCDD9DD8","x":8.0,"y":2.0,"sessionId":4444,"clusterStatus":"auto_done"}
                        ]}
                        """.trimIndent(),
                    )
                    path.contains("zone") -> MockResponse().setBody(
                        """
                        {"zones":[{"zone_id":"z-1","name":"작업구역 A","is_active":true,
                          "polygon":[[0.0,0.0],[5.0,0.0],[5.0,4.0],[0.0,4.0]]}]}
                        """.trimIndent(),
                    )
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        val state = client().loadFloorState(buildingId = "b-1", floorId = "14")

        // 도면만 없다.
        assertFalse(state.hasPlan)
        // 나머지는 전부 살아 있어야 한다.
        assertEquals(2, state.locators.size)
        assertEquals(4444, state.sessionId)
        assertEquals(1, state.zones.size)
        assertEquals("작업구역 A", state.zones.first().name)
    }

    /**
     * 도면이 없으면 존 폴리곤은 미터로 그대로 읽는다 — 픽셀인지 미터인지를 가르는 기준이
     * 도면 크기인데 그 도면이 없기 때문이다.
     */
    @Test fun zonePolygonIsReadAsMetersWhenNoPlan() = runTest {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path ?: ""
                return when {
                    path.endsWith("/plan") -> MockResponse().setBody("""{"has_plan":false,"floor_name":"3공장 2층","plan":null}""")
                    path.endsWith("/anchors") -> MockResponse().setBody("""{"anchors":[]}""")
                    path.contains("zone") -> MockResponse().setBody(
                        """
                        {"zones":[{"zone_id":"z-1","name":"A","is_active":true,
                          "polygon":[[2.5,3.5],[7.5,3.5],[7.5,9.0],[2.5,9.0]]}]}
                        """.trimIndent(),
                    )
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        val state = client().loadFloorState(buildingId = "b-1", floorId = "14")

        val pts = state.zones.first().polygon
        assertEquals(listOf(2.5, 7.5, 7.5, 2.5), pts.map { it.x })
        assertEquals(listOf(3.5, 3.5, 9.0, 9.0), pts.map { it.y })
    }

    /**
     * 도면이 있으면 존 폴리곤은 픽셀로 보고 미터로 변환한다 — widthM=10, imgW=1000,
     * imgH=500, origin(1,2) 일 때 점 (500,250) 은 (6.0, 4.5) 가 된다.
     * scale = imgW/widthM = 100. x = px/scale + originX = 5+1 = 6.0.
     * y = (imgH-py)/scale + originY = 2.5+2 = 4.5.
     */
    @Test fun zonePolygonPixelConversionWhenPlanExists() = runTest {
        val png = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=="
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path ?: ""
                return when {
                    path.endsWith("/plan") -> MockResponse().setBody(
                        """
                        {"has_plan":true,"floor_name":"넓은 층","plan":{"image":
                          {"data_url":"data:image/png;base64,$png","width_m":10.0,
                           "img_w":1000,"img_h":500,"origin_x":1.0,"origin_y":2.0}}}
                        """.trimIndent(),
                    )
                    path.endsWith("/anchors") -> MockResponse().setBody("""{"anchors":[]}""")
                    path.contains("zone") -> MockResponse().setBody(
                        """{"zones":[{"zone_id":"z-1","name":"A","is_active":true,"polygon":[[500.0,250.0],[600.0,250.0],[600.0,350.0]]}]}""",
                    )
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        val state = client().loadFloorState(buildingId = "b-1", floorId = "14")

        val first = state.zones.first().polygon.first()
        assertEquals(6.0, first.x, 0.0001)
        assertEquals(4.5, first.y, 0.0001)
    }

    /**
     * `has_plan: false` 면 공간 서비스 직행 폴백을 **타지 않는다.** 예전에 던지던 자리가
     * 정확히 여기다 — 콘솔이 "없다" 고 말했는데도 폴백을 탔고, 그쪽 응답 타입은 이미지가
     * 옵셔널이 아니라 디코드에서 터졌다.
     */
    @Test fun doesNotFallBackToSpaceWhenConsoleSaysNoPlan() = runTest {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path ?: ""
                return when {
                    path.endsWith("/plan") && path.contains("/positioning/") ->
                        MockResponse().setBody("""{"has_plan":false,"plan":null}""")
                    path.endsWith("/anchors") -> MockResponse().setBody("""{"anchors":[]}""")
                    path.contains("zone") -> MockResponse().setBody("""{"zones":[]}""")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        client().loadFloorState(buildingId = "b-1", floorId = "14")

        // 공간 서비스 직행 도면 경로(api/m/floors/…/plan)는 한 번도 불리지 않아야 한다.
        val requests = generateSequence { if (server.requestCount > 0) server.takeRequest() else null }
            .take(server.requestCount)
            .toList()
        val spacePlanCalls = requests.filter { it.path?.contains("api/m/floors") == true && it.path!!.endsWith("/plan") }
        assertTrue(
            "콘솔이 도면 없음을 명시했는데 공간 서비스 폴백을 탔다: ${spacePlanCalls.map { it.path }}",
            spacePlanCalls.isEmpty(),
        )
    }

    /** 도면이 있는 층은 예전 그대로 — 회귀 방지. */
    @Test fun floorWithPlanStillReportsHasPlan() = runTest {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path ?: ""
                return when {
                    path.endsWith("/plan") -> MockResponse().setBody(
                        """
                        {"has_plan":true,"floor_name":"607호","plan":{"image":
                          {"data_url":"data:image/png;base64,$png1x1","width_m":10.0,
                           "img_w":100,"img_h":50,"origin_x":0.0,"origin_y":0.0}}}
                        """.trimIndent(),
                    )
                    path.endsWith("/anchors") -> MockResponse().setBody("""{"anchors":[]}""")
                    path.contains("zone") -> MockResponse().setBody("""{"zones":[]}""")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        val state = client().loadFloorState(buildingId = "b-1", floorId = "14")

        assertTrue(state.hasPlan)
    }

    /**
     * 서버가 `cluster_status` 로 미배치를 알려주면 로케이터에 실려 와야 한다 — 측위를 켜 보기
     * 전에도 알 수 있는 유일한 고장 신호다.
     */
    @Test fun clusterStatusBecomesIsPlaced() = runTest {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path ?: ""
                return when {
                    path.endsWith("/plan") -> MockResponse().setBody("""{"has_plan":false,"plan":null}""")
                    path.endsWith("/anchors") -> MockResponse().setBody(
                        """
                        {"anchors":[
                          {"uwbMac":"AABBCCDD9DD7","x":1.0,"y":2.0,"sessionId":1,"clusterStatus":"auto_done"},
                          {"uwbMac":"AABBCCDD9DD8","x":2.0,"y":2.0,"sessionId":1,"clusterStatus":"apply_failed"},
                          {"uwbMac":"AABBCCDD9DD9","x":3.0,"y":2.0,"sessionId":1}
                        ]}
                        """.trimIndent(),
                    )
                    path.contains("zone") -> MockResponse().setBody("""{"zones":[]}""")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        val state = client().loadFloorState(buildingId = "b-1", floorId = "14")

        assertEquals(
            "cluster_status 해석이 바뀌었다",
            listOf(true, false, true), // auto_done / apply_failed(미배치) / 값 없음(모름=배치된 것으로 본다)
            state.locators.map { it.isPlaced },
        )
    }

    /**
     * `uwbMac="AA:BB:0B:4B"` 처럼 콜론 구분자가 섞여 와도 마지막 4 hex(콜론 제외)로 주소를
     * 만든다 — `address == 0x0B4B`.
     */
    @Test fun uwbMacWithColonsParsesLast4HexAsAddress() = runTest {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path ?: ""
                return when {
                    path.endsWith("/plan") -> MockResponse().setBody("""{"has_plan":false,"plan":null}""")
                    path.endsWith("/anchors") -> MockResponse().setBody(
                        """{"anchors":[{"uwbMac":"AA:BB:0B:4B","x":1.0,"y":2.0,"sessionId":1,"clusterStatus":"auto_done"}]}""",
                    )
                    path.contains("zone") -> MockResponse().setBody("""{"zones":[]}""")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        val state = client().loadFloorState(buildingId = "b-1", floorId = "14")

        assertEquals(1, state.locators.size)
        assertEquals(0x0B4B, state.locators.first().address)
    }
}
