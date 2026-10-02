package co.onecheck.ones1ght.android

import co.onecheck.ones1ght.android.model.Floor
import co.onecheck.ones1ght.android.space.SpaceServiceClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * 파사드 상태 — 감사 SF-A7(=iOS S12) · SF-A8 · SF-A9(=iOS S15).
 */
class FacadeStateTest {

    private lateinit var h: JavaInteropHarness

    @Before fun setUp() {
        h = JavaInteropHarness.start()
    }

    @After fun tearDown() {
        h.close()
    }

    private fun initialize(key: String = "ock_facade_state") {
        h.await { OneS1ght.initialize(h.context(), key, h.baseUrl()) }
    }

    /**
     * SF-A7 · iOS S12 — identify 를 initialize 보다 먼저 불러도 그 프로필로 측위가 열린다. 예전엔 새 코디네이터에
     * 프로필을 안 넘겨 begin() 이 NotIdentified(E1004)였다.
     */
    @Test fun identifyBeforeInitializeIsCarriedIntoCoordinator() {
        OneS1ght.identify("p-early")
        initialize()
        h.await { OneS1ght.floorSession().begin(h.mock) }
        assertTrue(OneS1ght.floorSession().isRunning)
        h.await { OneS1ght.floorSession().end() }
    }

    /** reset 뒤 다른 키로 다시 초기화해도 앱이 넘긴 프로필은 그대로 쓴다(앱은 identify 를 다시 부를 이유가 없다). */
    @Test fun identifySurvivesResetAndReinitialize() {
        initialize("ock_first")
        OneS1ght.identify("p-1")
        h.await { OneS1ght.reset() }
        initialize("ock_second")
        h.await { OneS1ght.floorSession().begin(h.mock) }
        assertTrue(OneS1ght.floorSession().isRunning)
        h.await { OneS1ght.floorSession().end() }
    }

    /**
     * SF-A8 — reset 은 건물 문맥도 지운다. 남아 있으면 다른 고객사로 다시 초기화한 뒤 setFloorMap(floor) 가
     * 옛 건물 ID 로 조회했다(404). 이제는 건물을 다시 넘기라고 거절한다(BuildingNotSet).
     */
    @Test fun resetForgetsBuildingContext() {
        h.enableSpaceService()
        initialize()
        h.await { OneS1ght.setFloorMap(Floor("14", "1F"), "b-old") }
        h.await { OneS1ght.reset() }
        initialize("ock_other_tenant")
        try {
            h.await { OneS1ght.setFloorMap(Floor("14", "1F")) }
            fail("reset 뒤에도 옛 건물 문맥으로 층을 열었다")
        } catch (e: SdkError.BuildingNotSet) {
            // 기대한 대로
        }
    }

    /**
     * SF-A9 · iOS S15 — initialize(baseUrl) 을 주면 공간 조회(건물·층·구역·도면)도 그 서버로 간다. 예전엔
     * 공간 조회만 늘 console.ones1ght.com 으로 나가 자체 서버 고객의 SDK 키가 우리 서버로 갔다.
     */
    @Test fun spaceLookupsUseInitializeBaseUrl() {
        h.routes.spaceKey = "gsk_facade" // 공간 조회 클라이언트가 만들어지게(주소 덮어쓰기는 하지 않는다)
        initialize()
        val client = OneS1ght.coordinatorRef?.spaceClient ?: error("공간 조회 클라이언트가 없다")
        assertEquals(h.baseUrl(), consoleBaseOf(client))
    }

    private fun consoleBaseOf(client: SpaceServiceClient): String {
        val f = SpaceServiceClient::class.java.getDeclaredField("consoleBase")
        f.isAccessible = true
        return f.get(client) as String
    }
}
