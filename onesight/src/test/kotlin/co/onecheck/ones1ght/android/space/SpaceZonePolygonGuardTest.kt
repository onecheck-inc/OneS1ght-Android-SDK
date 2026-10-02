package co.onecheck.ones1ght.android.space

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
 * 감사 SF-A2 — 서버가 준 존 폴리곤의 **점 하나**가 망가져도 층 전체가 안 열리면 안 된다.
 *
 * 예전엔 점 개수(size<3)만 보고 점 원소는 `it[0]`·`it[1]` 로 바로 읽었다. 콘솔이 `[[1.0],[2.0],[3.0]]`
 * 같은 값을 주면 IndexOutOfBounds 가 문서에 없는 예외로 새어 층이 통째로 실패했고, ApiError 만
 * 잡는 고객 앱은 죽었다. 이제 원소 2개 미만·비유한 값인 **점만** 거르고(iOS S23 과 같은 기준),
 * 남은 점이 3개 미만일 때만 그 존을 버린다. 걸러낸 사실은 로그 훅으로 남긴다.
 */
class SpaceZonePolygonGuardTest {

    @get:Rule val server = MockWebServer()

    private val notes = mutableListOf<String>()

    private fun client(): SpaceServiceClient = SpaceServiceClient(
        sdkKey = "ock_sdk_x",
        spaceKey = "gsk_x",
        http = OkHttpClient(),
        consoleBase = server.url("/api/sdk/v1").toString().trimEnd('/'),
        spaceHost = server.url("/").toString(),
    ).also { c -> c.onDataWarning = { notes += it } }

    /** 존 응답만 [zonesJson] 으로, 도면은 없음. */
    private fun serve(zonesJson: String) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path ?: ""
                return when {
                    path.endsWith("/plan") -> MockResponse().setBody("""{"has_plan":false,"floor_name":"1F","plan":null}""")
                    path.endsWith("/anchors") -> MockResponse().setBody("""{"anchors":[]}""")
                    path.contains("zone") -> MockResponse().setBody(zonesJson)
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
    }

    /** 점마다 원소가 하나뿐인 존은 버리고, 멀쩡한 존은 그대로 온다 — 층 조회는 실패하지 않는다. */
    private val badAndGood = """
        {"zones":[
          {"zone_id":"z-bad","name":"망가진 구역","is_active":true,"polygon":[[1.0],[2.0],[3.0]]},
          {"zone_id":"z-ok","name":"정상 구역","is_active":true,"polygon":[[0.0,0.0],[5.0,0.0],[5.0,4.0]]}
        ]}
    """.trimIndent()

    @Test fun zoneWithOnlyBadPointsIsDroppedOthersSurvive_loadFloorState() = runTest {
        serve(badAndGood)
        val state = client().loadFloorState(buildingId = "b-1", floorId = "14")
        assertEquals(listOf("z-ok"), state.zones.map { it.id })
        assertTrue(notes.toString(), notes.any { "z-bad" in it })
    }

    @Test fun zoneWithOnlyBadPointsIsDroppedOthersSurvive_loadZones() = runTest {
        serve(badAndGood)
        val zones = client().loadZones(buildingId = "b-1", floorId = "14")
        assertEquals(listOf("z-ok"), zones.map { it.id })
    }

    /** 나쁜 점만 거르고 존은 살린다 — 남은 점이 3개 이상이면. 원소가 3개 이상인 점은 앞의 둘(x, y)만 쓴다. */
    @Test fun badPointsAreSkippedZoneKeptWhenThreeRemain() = runTest {
        serve(
            """
            {"zones":[{"zone_id":"z-1","name":"A","is_active":true,
              "polygon":[[0.0,0.0],[9.0],[5.0,0.0],[],[5.0,4.0,1.5],[0.0,4.0]]}]}
            """.trimIndent(),
        )
        val zones = client().loadZones(buildingId = "b-1", floorId = "14")
        assertEquals(1, zones.size)
        val pts = zones.first().polygon
        assertEquals(listOf(0.0, 5.0, 5.0, 0.0), pts.map { it.x })
        assertEquals(listOf(0.0, 0.0, 4.0, 4.0), pts.map { it.y })
        assertTrue(notes.toString(), notes.any { "z-1" in it })
    }

    /** 걸러서 3개 미만이 되면 그 존만 버린다. */
    @Test fun zoneDroppedWhenFewerThanThreeValidPointsRemain() = runTest {
        serve(
            """
            {"zones":[{"zone_id":"z-1","name":"A","is_active":true,
              "polygon":[[0.0,0.0],[5.0,0.0],[5.0]]}]}
            """.trimIndent(),
        )
        assertEquals(emptyList<Any>(), client().loadZones(buildingId = "b-1", floorId = "14"))
    }

    /** 망가진 존이 이름을 선점하지 않는다 — 같은 이름의 멀쩡한 존이 뒤에 오면 그것이 산다. */
    @Test fun droppedZoneDoesNotReserveItsName() = runTest {
        serve(
            """
            {"zones":[
              {"zone_id":"z-bad","name":"A","is_active":true,"polygon":[[1.0],[2.0],[3.0]]},
              {"zone_id":"z-ok","name":"A","is_active":true,"polygon":[[0.0,0.0],[5.0,0.0],[5.0,4.0]]}
            ]}
            """.trimIndent(),
        )
        assertEquals(listOf("z-ok"), client().loadZones(buildingId = "b-1", floorId = "14").map { it.id })
    }
}
