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
 * 공간 조회 응답 방어 — 감사 SF-A5 · SF-A12 · SF-A14 · SF-A16 (iOS S17: 서버가 늘릴 수 있는 필드를
 * 요소 단위로 관대하게 읽는다).
 */
class SpaceResponseGuardTest {

    @get:Rule val server = MockWebServer()

    private val notes = mutableListOf<String>()
    private val duplicates = mutableListOf<Triple<String, String, String>>()

    private fun client(): SpaceServiceClient = SpaceServiceClient(
        sdkKey = "ock_sdk_x",
        spaceKey = "gsk_x",
        http = OkHttpClient(),
        consoleBase = server.url("/api/sdk/v1").toString().trimEnd('/'),
        spaceHost = server.url("/").toString(),
    ).also { c ->
        c.onDataWarning = { notes += it }
        c.onDuplicateZoneName = { name, kept, dropped -> duplicates += Triple(name, kept, dropped) }
    }

    private fun serve(zones: String = """{"zones":[]}""", anchors: String = """{"anchors":[]}""", floors: String? = null, buildings: String? = null) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path ?: ""
                return when {
                    path.endsWith("/plan") -> MockResponse().setBody("""{"has_plan":false,"floor_name":"1F","plan":null}""")
                    path.endsWith("/anchors") -> MockResponse().setBody(anchors)
                    path.contains("/zones") -> MockResponse().setBody(zones)
                    path.endsWith("/floors") && floors != null -> MockResponse().setBody(floors)
                    path.endsWith("/positioning/buildings") && buildings != null -> MockResponse().setBody(buildings)
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
    }

    // MARK: - SF-A5 — 항목 하나가 깨져도 목록은 산다

    /** 구역 하나의 name 이 null 이어도 나머지 구역은 온다(예전엔 zones() 전체 실패 → setFloorMap 은 0개, E3004). */
    @Test fun zoneWithNullNameIsDroppedOthersSurvive() = runTest {
        serve(
            zones = """
                {"zones":[
                  {"zone_id":"z-bad","name":null,"is_active":true,"polygon":[[0,0],[1,0],[1,1]]},
                  {"zone_id":"z-ok","name":"A","is_active":true,"polygon":[[0,0],[5,0],[5,4]],"in_dist":null,"priority":null}
                ]}
            """.trimIndent(),
        )
        assertEquals(listOf("z-ok"), client().loadZones("b-1", "14").map { it.id })
        val state = client().loadFloorState("b-1", "14")
        assertEquals(listOf("z-ok"), state.zones.map { it.id })
        assertTrue(notes.toString(), notes.any { "z-bad" in it })
    }

    /** 콘솔 층 목록 — 항목 하나가 깨져도 나머지 층이 온다(공간 서비스 우회로 빠지지 않는다). */
    @Test fun floorListSkipsBrokenItem() = runTest {
        serve(floors = """{"floors":[{"floor_id":null},{"floor_id":"14","name":"1F","has_plan":true}]}""")
        val floors = client().floors("b-1")
        assertEquals(listOf("14"), floors.map { it.id })
    }

    /** 콘솔 건물 목록 — 같은 규칙. */
    @Test fun buildingListSkipsBrokenItem() = runTest {
        serve(buildings = """{"buildings":[{"building_id":7},{"building_id":"b-1","name":"본점"}]}""")
        assertEquals(listOf("b-1"), client().buildings().map { it.id })
    }

    // MARK: - SF-A12 — 같은 이름 구역을 버리면 알린다

    @Test fun duplicateZoneNameIsReported() = runTest {
        serve(
            zones = """
                {"zones":[
                  {"zone_id":"z-1","name":"A","is_active":true,"polygon":[[0,0],[5,0],[5,4]]},
                  {"zone_id":"z-2","name":"A","is_active":true,"polygon":[[0,0],[6,0],[6,4]]}
                ]}
            """.trimIndent(),
        )
        assertEquals(listOf("z-1"), client().loadZones("b-1", "14").map { it.id })
        assertEquals(listOf(Triple("A", "z-1", "z-2")), duplicates)
    }

    // MARK: - SF-A14 — 세션 ID 는 첫 앵커만 보지 않는다

    @Test fun sessionIdTakenFromFirstAnchorThatHasIt() = runTest {
        serve(
            anchors = """
                {"anchors":[
                  {"uwbMac":"00:11","x":1.0,"y":1.0,"sessionId":null},
                  {"uwbMac":"00:12","x":2.0,"y":1.0,"sessionId":42}
                ]}
            """.trimIndent(),
        )
        assertEquals(42, client().loadFloorState("b-1", "14").sessionId)
        assertEquals(42, client().loadLocators("14")?.sessionId)
    }

    /** 앵커 하나가 깨져도(필드 타입 어긋남) 나머지 앵커는 온다. */
    @Test fun brokenAnchorSkipped() = runTest {
        serve(
            anchors = """
                {"anchors":[
                  {"uwbMac":"00:11","x":"one","y":1.0,"sessionId":7},
                  {"uwbMac":"00:12","x":2.0,"y":1.0,"sessionId":7}
                ]}
            """.trimIndent(),
        )
        val state = client().loadFloorState("b-1", "14")
        assertEquals(1, state.locators.size)
        assertEquals(false, state.locatorsFetchFailed)
    }

    // MARK: - SF-A16 — 경로 변수 인코딩

    @Test fun idsAreEncodedAsSinglePathSegments() = runTest {
        serve()
        client().loadZones("b/1", "14?x#y")
        val paths = generateSequence { server.takeRequest(0, java.util.concurrent.TimeUnit.MILLISECONDS) }.map { it.path!! }.toList()
        assertTrue(paths.toString(), paths.any { it.endsWith("/positioning/buildings/b%2F1/floor/14%3Fx%23y/zones") })
    }
}
