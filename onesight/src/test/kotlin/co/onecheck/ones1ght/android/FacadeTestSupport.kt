@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package co.onecheck.ones1ght.android

//
//  FacadeTestSupport.kt
//  OneS1ght 파사드 테스트 공용 — Kotlin 테스트와 JavaInteropTest.java 가 같이 쓴다.
//
//  · 파사드는 전역(object)이라 테스트마다 주입 자리(dispatcher·deviceCapability·platformFactory·
//    builtInProviderFactory)를 갈아 끼우고, close() 에서 reset + 원상복구한다.
//  · 코어 디스패처는 StandardTestDispatcher 다. 가상 시간은 흘리지 않는다 — 60초 flush 타이머가
//    무한 반복이라 advanceUntilIdle 은 끝나지 않는다. 대신 runCurrent 를 실시간으로 돌리며
//    OkHttp 스레드에서 돌아오는 응답을 이어 준다(CoordinatorTestSupport.eventually 와 같은 방식).
//  · Context 는 ContextWrapper(null) — returnDefaultValues 라 packageName·applicationContext 는 null.
//

import android.content.Context
import android.content.ContextWrapper
import co.onecheck.ones1ght.android.positioning.DeviceCapability
import co.onecheck.ones1ght.android.positioning.MockPositioningProvider
import co.onecheck.ones1ght.android.positioning.PositioningProvider
import co.onecheck.ones1ght.android.runtime.InMemoryKeyValueStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.fail
import java.util.concurrent.CopyOnWriteArrayList

/** 기기 판정 가짜 — sdkInt·지원 여부를 테스트가 정한다. */
internal class FakeDeviceCapability(
    override var sdkInt: Int = 37,
    supported: Boolean = true,
) : DeviceCapability {
    @Volatile var queries: Int = 0

    /** 바꾸면 기억한 답도 잊는다 — 테스트가 판정을 뒤집어 보는 경우. */
    @Volatile var supported: Boolean = supported
        set(value) {
            field = value
            known = null
        }

    /** 실기기 구현처럼 한 번 물은 답을 기억한다. */
    @Volatile private var known: Boolean? = null

    override val cachedDlTdoa: Boolean? get() = known

    override suspend fun supportsDlTdoa(): Boolean {
        queries += 1
        return supported.also { known = it }
    }
}

/** 요청 한 건 — 경로와 SDK 키 헤더. */
internal data class FacadeRequest(val path: String, val sdkKey: String?)

/** 경로별 응답 + 요청 기록. */
internal class FacadeRoutes : Dispatcher() {
    val requests = CopyOnWriteArrayList<FacadeRequest>()

    @Volatile var verifyStatus: Int = 200

    override fun dispatch(request: RecordedRequest): MockResponse {
        val path = request.requestUrl?.encodedPath ?: (request.path ?: "")
        requests.add(FacadeRequest(path, request.getHeader("X-SDK-Key")))
        return when {
            path.endsWith("/auth/verify") ->
                if (verifyStatus == 200) {
                    json("""{ "valid": true, "tenant_code": "t", "positioning_enabled": true }""")
                } else {
                    json("""{ "detail": "invalid key" }""", verifyStatus)
                }
            // 세 값을 서로 다르게 — 자리가 바뀌면 단언이 반드시 깨지게 한다(iOS 와 같은 의도).
            // geo_sdk_key 는 주지 않는다: 주면 공간 서비스 클라이언트가 실제 호스트로 나간다.
            path.endsWith("/config") -> json(
                """{ "google_map_key": "AIza_facade", "geo_partner_key": "gpk_facade",
                     "geo_base_url": "https://space.facade.test" }""",
            )
            else -> json("{}")
        }
    }

    fun count(suffix: String): Int = requests.count { it.path.endsWith(suffix) }

    private fun json(body: String, code: Int = 200): MockResponse =
        MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)
}

/**
 * 파사드 테스트 하네스. `start()` 가 주입 자리를 갈아 끼우고 `close()` 가 되돌린다.
 * Java 에서도 부를 수 있게 멤버는 전부 평범한 메서드로 둔다.
 */
internal class JavaInteropHarness private constructor() {

    val server = MockWebServer()
    val routes = FacadeRoutes()
    val scheduler = TestCoroutineScheduler()
    val dispatcher: CoroutineDispatcher = StandardTestDispatcher(scheduler)
    val capability = FakeDeviceCapability()
    val mock = MockPositioningProvider()

    /** 내장 provider 자리에 넣을 것 — 기본은 [mock]. 만든 횟수는 [builtInCreated]. */
    var builtIn: PositioningProvider = mock
    @Volatile var builtInCreated: Int = 0

    /** runCurrent 를 돌리는 스레드 — 코어 디스패처의 "메인 스레드" 자리. */
    val coreThread: Thread = Thread.currentThread()

    private val context: Context = ContextWrapper(null)

    fun context(): Context = context

    fun baseUrl(): String = server.url("/api/sdk/v1").toString().trimEnd('/')

    fun mockProvider(): PositioningProvider = mock

    /** Java 콜백 판이 전부 끝날 때까지(콜백 전달 포함) 실시간으로 기다린다. */
    fun drain() {
        eventually("Java 콜백 미완료") {
            JavaBridge.inFlight.get() == 0
        }
    }

    /** suspend 판을 코어 디스패처에서 돌리고 끝날 때까지 기다린다 — 예외는 그대로 다시 던진다. */
    fun <T> await(block: suspend () -> T): T = await(dispatcher, block)

    /** [on] 디스패처에서 시작한 코루틴으로 suspend 판을 부른다(스레드 전환 검증용). */
    fun <T> await(on: CoroutineDispatcher, block: suspend () -> T): T {
        val d: Deferred<T> = CoroutineScope(on).async { block() }
        eventually("suspend 호출 미완료") { d.isCompleted }
        val err = d.getCompletionExceptionOrNull()
        if (err != null) throw err
        return d.getCompleted()
    }

    /** 조건이 설 때까지 실시간으로 기다리며 코어 디스패처의 지금 작업을 돌린다(최대 5초). */
    fun eventually(message: String = "조건 미충족", cond: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000L * 1_000_000
        while (true) {
            scheduler.runCurrent()
            if (cond()) {
                scheduler.runCurrent()
                return
            }
            if (System.nanoTime() > deadline) fail(message)
            Thread.sleep(2)
        }
    }

    fun close() {
        try {
            await { OneS1ght.reset() }
        } finally {
            FloorSession.shared.clearForTest()
            OneS1ght.onDebugLog = null
            OneS1ght.identify(null)
            OneS1ght.restoreDefaultsForTest()
            server.shutdown()
        }
    }

    private fun install() {
        server.dispatcher = routes
        server.start()
        OneS1ght.dispatcher = dispatcher
        OneS1ght.deviceCapability = capability
        // 앱 Context 를 이미 아는 상태(= initialize·permissions 이후)로 시작한다. "그 전" 동작은
        // 그 테스트가 직접 null 로 되돌려 본다.
        OneS1ght.appContext = context
        OneS1ght.platformFactory = { InMemoryKeyValueStore() to null }
        OneS1ght.builtInProviderFactory = {
            builtInCreated += 1
            builtIn
        }
        FloorSession.shared.clearForTest()
    }

    companion object {
        @JvmStatic
        fun start(): JavaInteropHarness = JavaInteropHarness().also { it.install() }
    }
}
