package co.onecheck.ones1ght.android.space

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * 앵커 TTL 캐시([SpaceServiceClient.ANCHOR_TTL_MS] = 180초, 주입된 `clock`)와 도면 캐시
 * (TTL 없음, 인스턴스 생존 동안 영구) — 둘 다 "같은 층을 다시 열어도 왕복이 늘지 않는다"를
 * 지킨다. Fix round 1: 코드 리뷰에서 지적된 커버리지 공백을 메운다(4개 iOS 포팅 테스트에는
 * 캐시 시나리오가 없었다).
 */
class SpaceServiceClientCacheTest {

    @get:Rule val server = MockWebServer()

    /** [SpaceServiceClient] 의 `clock` 주입점 — 테스트가 시간을 직접 밀어 TTL 경계를 만든다. */
    private var fakeNow = 0L

    private fun client(): SpaceServiceClient = SpaceServiceClient(
        sdkKey = "ock_sdk_x",
        spaceKey = "gsk_x",
        http = OkHttpClient(),
        consoleBase = server.url("/api/sdk/v1").toString().trimEnd('/'),
        spaceHost = server.url("/").toString(),
        clock = { fakeNow },
    )

    /** 경로별 요청 횟수를 세면서 늘 성공 응답을 주는 디스패처. */
    private fun countingDispatcher(anchorRequests: AtomicInteger, planRequests: AtomicInteger): Dispatcher =
        object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path ?: ""
                return when {
                    path.endsWith("/anchors") -> {
                        anchorRequests.incrementAndGet()
                        MockResponse().setBody("""{"anchors":[]}""")
                    }
                    path.endsWith("/plan") -> {
                        planRequests.incrementAndGet()
                        MockResponse().setBody("""{"has_plan":false,"floor_name":"14층","plan":null}""")
                    }
                    path.contains("zone") -> MockResponse().setBody("""{"zones":[]}""")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

    /** (a) TTL 안이면 두 번째 `loadFloorState` 가 앵커를 다시 부르지 않는다. */
    @Test fun anchorCacheHitWithinTtlMakesNoNewRequest() = runTest {
        val anchorRequests = AtomicInteger(0)
        val planRequests = AtomicInteger(0)
        server.dispatcher = countingDispatcher(anchorRequests, planRequests)
        val c = client()

        c.loadFloorState(buildingId = "b-1", floorId = "14")
        assertEquals(1, anchorRequests.get())

        // 시계를 전혀 움직이지 않는다 — TTL(180초) 안.
        c.loadFloorState(buildingId = "b-1", floorId = "14")
        assertEquals("TTL 안인데 앵커를 다시 불렀다", 1, anchorRequests.get())
    }

    /** (b) TTL(180초)을 넘겨 시계를 밀면 다시 부른다. */
    @Test fun anchorCacheExpiresAfterTtl() = runTest {
        val anchorRequests = AtomicInteger(0)
        val planRequests = AtomicInteger(0)
        server.dispatcher = countingDispatcher(anchorRequests, planRequests)
        val c = client()

        c.loadFloorState(buildingId = "b-1", floorId = "14")
        assertEquals(1, anchorRequests.get())

        fakeNow += SpaceServiceClient.ANCHOR_TTL_MS // 경계 그 자체도 만료로 친다(now - at < TTL 만 캐시 인정)
        c.loadFloorState(buildingId = "b-1", floorId = "14")
        assertEquals("TTL 을 넘겼는데 캐시를 계속 썼다", 2, anchorRequests.get())
    }

    /** (c) 도면 캐시 — 같은 층을 다시 `loadFloor` 해도 plan 요청은 한 번뿐이다(TTL 없음). */
    @Test fun planCacheHitAvoidsSecondPlanRequestForSameFloor() = runTest {
        val anchorRequests = AtomicInteger(0)
        val planRequests = AtomicInteger(0)
        server.dispatcher = countingDispatcher(anchorRequests, planRequests)
        val c = client()

        c.loadFloor(buildingId = "b-1", floorId = "14")
        assertEquals(1, planRequests.get())

        c.loadFloor(buildingId = "b-1", floorId = "14")
        assertEquals("같은 층인데 도면을 다시 불렀다", 1, planRequests.get())
    }

    /** 도면 캐시는 층별이다 — 다른 floorId 는 캐시를 타지 않고 새로 부른다. */
    @Test fun planCacheIsPerFloor() = runTest {
        val anchorRequests = AtomicInteger(0)
        val planRequests = AtomicInteger(0)
        server.dispatcher = countingDispatcher(anchorRequests, planRequests)
        val c = client()

        c.loadFloor(buildingId = "b-1", floorId = "14")
        c.loadFloor(buildingId = "b-1", floorId = "15")
        assertEquals(2, planRequests.get())
    }
}
