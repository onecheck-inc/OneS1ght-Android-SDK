@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package co.onecheck.ones1ght.android.runtime

//
//  CoordinatorTestSupport.kt
//  SessionCoordinator 테스트 공용 — iOS 의 StubURLProtocol·makeStubSession 자리.
//
//  · 코디네이터 스코프는 runTest 의 backgroundScope 다. 60초 flush 타이머가 무한 반복이라,
//    전경 스코프에 두면 runTest 가 끝나지 않는다(backgroundScope 는 테스트 끝에 취소된다).
//  · OkHttp 응답은 실제 스레드에서 돌아온다 — [eventually] 가 실시간으로 기다리며
//    가상 스케줄러를 돌린다(iOS waitUntil 자리).
//

import co.onecheck.ones1ght.android.identity.IdentityStore
import co.onecheck.ones1ght.android.model.ConfigChange
import co.onecheck.ones1ght.android.network.ApiClient
import co.onecheck.ones1ght.android.positioning.PositioningProvider
import co.onecheck.ones1ght.android.space.SpaceServiceClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.TestScope
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.fail
import java.util.concurrent.CopyOnWriteArrayList

/** 요청 한 건의 기록 — 경로와 본문(문자열). */
internal data class Recorded(val path: String, val method: String, val body: String)

/** 경로별 canned 응답 + 요청 기록. [handler] 는 OkHttp·MockWebServer 스레드에서 돈다. */
internal class Routes : Dispatcher() {
    val requests = CopyOnWriteArrayList<Recorded>()

    @Volatile var handler: (path: String) -> MockResponse = { MockResponse().setResponseCode(500) }

    override fun dispatch(request: RecordedRequest): MockResponse {
        val path = request.requestUrl?.encodedPath ?: (request.path ?: "")
        requests.add(Recorded(path, request.method ?: "", request.body.readUtf8()))
        return handler(path)
    }

    fun paths(): List<String> = requests.map { it.path }

    fun count(suffix: String): Int = requests.count { it.path.endsWith(suffix) }

    fun reset() = requests.clear()
}

internal fun json(body: String, code: Int = 200): MockResponse =
    MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

internal const val VERIFY_OK = """{ "valid": true, "tenant_code": "t", "positioning_enabled": true }"""

/** 가상 시계의 기준점 — 2026-09-28T00:00:00Z. */
internal const val BASE_MS = 1_790_553_600_000L

/**
 * 코디네이터를 만든다. 서버는 [server], 스코프는 [TestScope.backgroundScope].
 * OkHttp 의 연결 실패 자동 재시도를 끈다 — 켜 두면 끊긴 요청을 OkHttp 가 몰래 다시 보내
 * 코디네이터의 "network 1회 재시도" 경로가 시험되지 않는다.
 */
internal fun TestScope.makeCoordinator(
    server: MockWebServer,
    apiKey: String = "test-key",
    lifecycle: AppLifecycle? = null,
    flushThreshold: Int = 100,
    receptionCheckDelayMs: Long = 7_000,
    engineRestartDelaysMs: List<Long> = listOf(3_000L, 10_000L, 30_000L),
    spaceClients: MutableList<Pair<String, String>>? = null,
    liveFactory: ((ConfigChange) -> Unit, (LogLevel, String) -> Unit) -> LiveConfigStream? = { _, _ -> null },
): SessionCoordinator {
    val http = OkHttpClient.Builder().retryOnConnectionFailure(false).build()
    val base = server.url("/api/sdk/v1").toString().trimEnd('/')
    val api = ApiClient.create(apiKey, base, http)
    return SessionCoordinator(
        api = api,
        identity = IdentityStore.create(InMemoryKeyValueStore(), today = { "20260928" }),
        appId = "co.onecheck.test",
        scope = backgroundScope,
        lifecycle = lifecycle,
        clock = { BASE_MS + testScheduler.currentTime },
        spaceClientFactory = { sdk, space, _ ->
            spaceClients?.add(sdk to space)
            SpaceServiceClient(sdk, space, api.http, consoleBase = base, spaceHost = server.url("/").toString())
        },
        liveFactory = liveFactory,
        flushThreshold = flushThreshold,
        receptionCheckDelayMs = receptionCheckDelayMs,
        engineRestartDelaysMs = engineRestartDelaysMs,
    )
}

/**
 * 조건이 설 때까지 실시간으로 기다리며 가상 스케줄러의 지금 시각 작업을 돌린다(최대 [timeoutMs]).
 * 네트워크 응답은 OkHttp 스레드에서 테스트 디스패처로 돌아오므로 runCurrent 로 이어 준다.
 */
internal fun TestScope.eventually(timeoutMs: Long = 3_000, message: String = "조건 미충족", cond: () -> Boolean) {
    val deadline = System.nanoTime() + timeoutMs * 1_000_000
    while (true) {
        testScheduler.runCurrent()
        if (cond()) return
        if (System.nanoTime() > deadline) fail("$message (${timeoutMs}ms)")
        Thread.sleep(5)
    }
}

/** 조건이 [ms] 동안 한 번도 서지 않는지 본다 — "요청이 안 나간다" 류 검증용. */
internal fun TestScope.never(ms: Long = 200, message: String = "일어나면 안 되는 일이 일어났다", cond: () -> Boolean) {
    val deadline = System.nanoTime() + ms * 1_000_000
    while (System.nanoTime() < deadline) {
        testScheduler.runCurrent()
        if (cond()) fail(message)
        Thread.sleep(5)
    }
}

/**
 * [scope] 의 코루틴이 전부 끝날 때까지 기다린다(조건 대기 — 감사 K16 · SF-T1). 고정 시간(Thread.sleep)을 재지 않고
 * 「더 일어날 일이 없다」를 확인할 때 쓴다: 스트림을 stop() 한 뒤 수신 코루틴이 실제로 끝났으면, 그 뒤로는 재연결도
 * 로그도 생길 수 없다. 끝나지 않으면(연결이 안 끊겼으면) 실패한다.
 */
internal fun awaitNoActiveChildren(scope: CoroutineScope, timeoutMs: Long = 5_000, message: String = "코루틴이 끝나지 않았다") {
    val job = scope.coroutineContext[Job] ?: error("scope 에 Job 이 없다")
    val deadline = System.nanoTime() + timeoutMs * 1_000_000
    while (job.children.any { it.isActive }) {
        if (System.nanoTime() > deadline) fail("$message (${timeoutMs}ms)")
        Thread.yield()
    }
}

/**
 * 코디네이터 테스트 공용 준비 — 스텁 서버·경로·기본 응답·로그 수집·「가동까지」(감사 K16 · iOS CoordinatorFixture).
 * 예전엔 테스트 파일마다 같은 verify 응답·prepare→identify→start 순서를 복사해 두었다.
 */
internal class CoordinatorFixture(val server: MockWebServer) {
    val routes = Routes()

    /** 코디네이터가 남긴 화면 로그(등급, 줄). */
    val lines: MutableList<Pair<LogLevel, String>> = CopyOnWriteArrayList()

    init {
        server.dispatcher = routes
        routes.handler = ::defaultResponse
    }

    /** 기본 응답 — verify 통과, 서버 로그 accepted, 그 밖은 빈 객체. 테스트가 [Routes.handler] 를 바꿔 덮는다. */
    fun defaultResponse(path: String): MockResponse = when {
        path.endsWith("/auth/verify") -> json(VERIFY_OK)
        path.endsWith("/logs") -> json("""{ "accepted_count": 1 }""")
        else -> json("{}")
    }

    /** 화면 로그에 [code] 줄이 있는가. */
    fun hasCode(code: String): Boolean = lines.any { it.second.contains("[$code]") }

    /** [code] 로 시작하는 줄들. */
    fun codeLines(code: String): List<String> = lines.map { it.second }.filter { it.startsWith("[$code]") }

    /** 코디네이터를 만들어 로그를 모으고 초기화 → 프로필 → 가동까지 한다. */
    suspend fun TestScope.started(
        provider: PositioningProvider,
        profileId: String = "pf_8a3c",
        lifecycle: AppLifecycle? = null,
        flushThreshold: Int = 100,
        engineRestartDelaysMs: List<Long> = listOf(3_000L, 10_000L, 30_000L),
    ): SessionCoordinator {
        val c = makeCoordinator(
            server,
            lifecycle = lifecycle,
            flushThreshold = flushThreshold,
            engineRestartDelaysMs = engineRestartDelaysMs,
        )
        c.onLog = { level, line -> lines += level to line }
        c.prepare()
        c.identify(profileId)
        c.start(provider)
        return c
    }
}

/** 가짜 앱 생명주기 — 테스트가 배경/전경 전환을 직접 쏜다. */
internal class FakeAppLifecycle : AppLifecycle {
    var onBackground: (() -> Unit)? = null
    var onForeground: (() -> Unit)? = null
    var observeCount = 0
    var stopCount = 0

    val isObserving: Boolean get() = onBackground != null

    override fun observe(onBackground: () -> Unit, onForeground: () -> Unit) {
        observeCount += 1
        this.onBackground = onBackground
        this.onForeground = onForeground
    }

    override fun stopObserving() {
        stopCount += 1
        onBackground = null
        onForeground = null
    }

    fun background() = onBackground?.invoke()

    fun foreground() = onForeground?.invoke()
}
