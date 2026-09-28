package co.onecheck.ones1ght.android.runtime

import co.onecheck.ones1ght.android.model.ConfigChange
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 실제 수신 경로 — 네트워크 바이트가 [ConfigChange] 로 나오기까지.
 *
 * 이 테스트가 없으면 실기기에서 한참을 헤맬 수 있다. 연결 직후의 ResyncNeeded 는 파서를
 * 거치지 않고 나가기 때문에 "연결됨" 로그와 첫 갱신은 정상으로 보이지만, 프레임 조립이
 * 깨지면 zones.changed 같은 실제 이벤트는 하나도 도착하지 않는다 — 증상은 "새로고침을
 * 눌러야 갱신된다" 뿐이라 원인이 잘 드러나지 않는다. 그래서 여기서 지키는 것은
 * **프레임이 실제로 조립되는가** 다. 페이로드는 iOS 쪽 테스트가 옮겨둔 2026-08-25 prod
 * `GET /api/sdk/v1/stream` 응답을 그대로 쓴다 — 하트비트 주석과 빈 줄 구분까지 포함해서.
 *
 * 포팅 원본: LiveConfigStreamIngestTests.swift.
 */
class LiveConfigStreamIngestTest {

    @get:Rule val server = MockWebServer()

    // ⚠️ scope 디스패처는 일부러 IO 와 다른, 이름이 있는 단일 스레드로 둔다 — consume()/
    // readFrames() 는 블로킹 소켓 호출만 Dispatchers.IO 로 넘기고 onConnected/ingest
    // (= onChange 호출, lastSeq 갱신)는 이 스레드로 돌아와서 돌아야 한다. scope 를 IO 로
    // 두면 이 분리가 우연히 맞아떨어져도 티가 안 난다 — 그래서 이름 있는 별도 스레드로
    // 두고 onChangeAndLogRunOnScopeDispatcherNotIo() 에서 스레드 이름으로 직접 검증한다.
    private val scopeThreadName = "lcs-test-scope"
    private val scopeExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, scopeThreadName) }
    private val scope = CoroutineScope(scopeExecutor.asCoroutineDispatcher() + SupervisorJob())

    @After
    fun tearDown() {
        scope.cancel()
        scopeExecutor.shutdownNow()
    }

    /** prod 실응답. 프레임 사이는 빈 줄 하나로 구분된다(콜론 뒤 공백 없음 — 실제 포맷). */
    private val realStream =
        "event:hello\n" +
            "data:{\"seq\":36,\"tenant_id\":1}\n" +
            "\n" +
            ":ping\n" +
            "\n" +
            "event:zones.changed\n" +
            "data:{\"seq\":37,\"tenant_id\":1,\"store_id\":3,\"building_id\":\"b-1\",\"floor_id\":\"14\"," +
            "\"origin\":\"agent.execute\"}\n" +
            "\n" +
            "event:rules.changed\n" +
            "data:{\"seq\":38,\"tenant_id\":1,\"store_id\":3,\"zone_id\":264,\"rule_id\":120,\"status\":\"active\"}\n" +
            "\n"

    private fun stream(onChange: (ConfigChange) -> Unit): LiveConfigStream =
        LiveConfigStream(
            http = OkHttpClient(),
            baseUrl = server.url("/api/sdk/v1").toString().trimEnd('/'),
            apiKey = "ock_test",
            scope = scope,
            onChange = onChange,
            log = { _, _ -> },
        )

    /** 핵심 — 서버가 보낸 이벤트가 호스트까지, 순서 그대로 도착해야 한다. */
    @Test
    fun deliversEventsFromRealServerPayload() {
        server.enqueue(MockResponse().setBody(realStream))

        val got = mutableListOf<ConfigChange>()
        val done = CountDownLatch(3) // resyncNeeded(연결) + zones.changed + rules.changed
        val liveConfigStream = stream { change ->
            got.add(change)
            done.countDown()
        }

        liveConfigStream.start(buildingId = "b-1", floorId = "14")
        assertTrue(done.await(5, TimeUnit.SECONDS))
        liveConfigStream.stop()

        // 연결 자체가 올리는 신호는 파서를 거치지 않는다 — 이게 통과한다고 수신이 되는 게
        // 아니다. 순서까지 정확히 맞아야 한다: ResyncNeeded(연결) → zones.changed → rules.changed.
        assertEquals(
            listOf(
                ConfigChange.ResyncNeeded,
                ConfigChange.ZonesChanged(floorId = "14"),
                ConfigChange.RulesChanged(zoneId = "264"),
            ),
            got,
        )

        val req = server.takeRequest()
        assertEquals("GET", req.method)
        assertEquals("/api/sdk/v1/stream?buildingId=b-1&floorId=14", req.path)
        assertEquals("ock_test", req.getHeader("X-SDK-Key"))
        assertEquals("text/event-stream", req.getHeader("Accept"))
    }

    /** 하트비트 주석만 오는 동안에는 호스트에 아무것도 올리지 않는다. */
    @Test
    fun heartbeatAloneDeliversNothingBeyondConnect() {
        server.enqueue(MockResponse().setBody(":ping\n\n:ping\n\n"))

        val got = mutableListOf<ConfigChange>()
        val firstChange = CountDownLatch(1)
        val liveConfigStream = stream { change ->
            got.add(change)
            firstChange.countDown()
        }

        liveConfigStream.start(buildingId = null, floorId = null)
        assertTrue(firstChange.await(5, TimeUnit.SECONDS))
        Thread.sleep(300) // 최소 재연결 백오프(1초 근방)보다 한참 짧게 — 하트비트 외에 더 오는지 확인할 여유
        liveConfigStream.stop()

        assertEquals(listOf(ConfigChange.ResyncNeeded), got)

        val req = server.takeRequest()
        assertEquals("/api/sdk/v1/stream", req.path) // null 파라미터는 생략
    }

    /**
     * ⚠️ 회귀 테스트 — [onChange]/[log] 콜백이 IO 스레드가 아니라 [scope] 의 디스패처
     * (스레드 이름 [scopeThreadName])에서 불려야 한다. `consume()`/`readFrames()` 전체를
     * `withContext(Dispatchers.IO)` 로 감쌌던 이전 구현에서는 이게 깨졌었다(코어 상태·공개
     * 콜백은 주입된 디스패처 한 곳에서만 바뀌어야 한다는 바인딩 제약 위반).
     */
    @Test
    fun onChangeAndLogRunOnScopeDispatcherNotIo() {
        server.enqueue(MockResponse().setBody(realStream))

        val onChangeThreads = mutableListOf<String>()
        val logThreads = mutableListOf<String>()
        val done = CountDownLatch(3)
        val liveConfigStream = LiveConfigStream(
            http = OkHttpClient(),
            baseUrl = server.url("/api/sdk/v1").toString().trimEnd('/'),
            apiKey = "ock_test",
            scope = scope,
            onChange = {
                onChangeThreads.add(Thread.currentThread().name)
                done.countDown()
            },
            log = { _, _ -> logThreads.add(Thread.currentThread().name) },
        )

        liveConfigStream.start(buildingId = "b-1", floorId = "14")
        assertTrue(done.await(5, TimeUnit.SECONDS))
        liveConfigStream.stop()

        // kotlinx.coroutines 디버그 모드(테스트 실행 시 기본 on)는 코루틴이 도는 동안
        // 스레드 이름 뒤에 " @coroutine#N" 을 붙인다 — 그래서 정확히 같지 않고 접두어로 잰다.
        assertTrue(
            "onChange 가 IO 스레드에서 불렸다(scope 스레드는 '$scopeThreadName') — 실제: $onChangeThreads",
            onChangeThreads.isNotEmpty() && onChangeThreads.all { it.startsWith(scopeThreadName) },
        )
        assertTrue(
            "log 가 IO 스레드에서 불렸다(scope 스레드는 '$scopeThreadName') — 실제: $logThreads",
            logThreads.isNotEmpty() && logThreads.all { it.startsWith(scopeThreadName) },
        )
    }

    /**
     * ⚠️ 회귀 테스트 — `currentCall` 을 다음 세대(generation)가 이미 갈아치운 뒤에도 이전
     * 세대의 `finally` 가 무조건 null 로 밀면, 그 뒤 [LiveConfigStream.stop] 이 "현재" 살아있는
     * 연결을 더는 취소하지 못한다. 몸통을 아주 느리게(1바이트/초) 흘려보내는 응답 두 개를
     * 큐에 놓고 — 취소되지 않으면 각각 완성되기까지 ~1000초가 걸린다 — 두 번째 [start] 가
     * 첫 번째 연결을 실제로 즉시 끊는지, 마지막 [stop] 이 두 번째 연결을 실제로 끊어
     * 재연결 루프까지 멈추는지(= 세 번째 요청이 없는지) 확인한다.
     */
    @Test
    fun secondStartCancelsFirstConnectionAndStopStopsTheReconnectLoop() {
        fun hangingResponse(): MockResponse =
            MockResponse().setBody("x".repeat(1000)).throttleBody(1, 1, TimeUnit.SECONDS)

        server.enqueue(hangingResponse())
        server.enqueue(hangingResponse())

        val resyncCount = AtomicInteger(0)
        val firstConnected = CountDownLatch(1)
        val secondConnected = CountDownLatch(1)
        val liveConfigStream = stream { change ->
            if (change == ConfigChange.ResyncNeeded) {
                when (resyncCount.incrementAndGet()) {
                    1 -> firstConnected.countDown()
                    2 -> secondConnected.countDown()
                }
            }
        }

        liveConfigStream.start(buildingId = "a", floorId = null)
        assertTrue("첫 연결이 안 됐다", firstConnected.await(5, TimeUnit.SECONDS))

        // 첫 연결은 몸통을 1000초에 걸쳐 흘려보내는 중이다(= 절대 스스로 안 끝난다). 그런데도
        // 두 번째 연결이 금방 붙는다면 start() 안의 stop() 이 첫 연결을 실제로 끊었다는 뜻이다.
        liveConfigStream.start(buildingId = "b", floorId = null)
        assertTrue(
            "두 번째 연결이 제때 안 됐다 — 첫 연결이 안 끊겼을 수 있다",
            secondConnected.await(5, TimeUnit.SECONDS),
        )

        liveConfigStream.stop()

        // 두 번째 연결도 끊겨야 재연결 루프가 멈춘다 — currentCall 이 그 사이 지워지지
        // 않았다면(고친 버그) 세 번째 요청은 없어야 한다.
        Thread.sleep(500)
        assertEquals(
            "stop() 뒤에 예상 밖의 재연결이 있었다 — 두 번째 연결이 제대로 안 끊겼다는 뜻이다",
            2,
            server.requestCount,
        )
    }
}
