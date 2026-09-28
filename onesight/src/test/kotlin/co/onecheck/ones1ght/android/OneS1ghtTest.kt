package co.onecheck.ones1ght.android

//
//  OneS1ghtTest.kt
//  OneS1ght 파사드(정적 진입점)·FloorSession·Java 브리지 자체를 검증한다.
//
//  포팅 원본: OneS1ghtTests.swift (버전 형식 · 콘솔 제공 값이 파사드를 실제로 거치는가).
//  나머지는 안드로이드 파사드가 새로 진 계약 — 코어 디스패처 한정, Java 콜백 판,
//  내장 provider 싱글턴·구역 이벤트 배선, 권한 분기.
//

import co.onecheck.ones1ght.android.model.ConfigChange
import co.onecheck.ones1ght.android.model.Coordinates
import co.onecheck.ones1ght.android.model.Position
import co.onecheck.ones1ght.android.model.Trigger
import co.onecheck.ones1ght.android.model.Zone
import co.onecheck.ones1ght.android.model.ZoneEvent
import co.onecheck.ones1ght.android.network.ApiError
import co.onecheck.ones1ght.android.positioning.PositioningPermission
import co.onecheck.ones1ght.android.positioning.PositioningProvider
import co.onecheck.ones1ght.android.positioning.PositioningProviderDelegate
import co.onecheck.ones1ght.android.positioning.RangingEngine
import co.onecheck.ones1ght.android.positioning.UwbPositioningProvider
import co.onecheck.ones1ght.android.runtime.LogLevel
import co.onecheck.ones1ght.android.zone.CoroutineDwellScheduler
import co.onecheck.ones1ght.android.zone.ZoneEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class OneS1ghtTest {

    private lateinit var h: JavaInteropHarness

    @Before fun setUp() {
        h = JavaInteropHarness.start()
    }

    @After fun tearDown() {
        h.close()
    }

    private fun initialize(key: String = "ock_facade_probe") {
        h.await { OneS1ght.initialize(h.context(), key, h.baseUrl()) }
    }

    private inline fun <reified E : Throwable> assertThrows(block: () -> Unit): E {
        try {
            block()
        } catch (e: Throwable) {
            if (e is E) return e
            throw AssertionError("기대한 예외 ${E::class.simpleName} 대신 $e", e)
        }
        fail("${E::class.simpleName} 이 던져지지 않았다")
        throw IllegalStateException()
    }

    // MARK: - OneS1ghtTests.swift

    /** 버전 형식만 본다 — 값 대조는 SnippetsTest·MigrationsTest 가 지킨다. */
    @Test fun sdkVersionIsSemver() {
        val v = OneS1ght.SDK_VERSION
        assertFalse(v.isEmpty())
        assertTrue("버전이 x.y.z 형식이 아니다: $v", Regex("""^\d+\.\d+\.\d+$""").matches(v))
    }

    /** initialize 전에는 null — 던지지 않는다. */
    @Test fun valuesAreNullBeforeInitialize() {
        assertNull(OneS1ght.googleMapKey)
        assertFalse(OneS1ght.isInitialized)
    }

    /** initialize 이후 값이 콘솔 응답과 정확히 일치 — 파사드를 실제로 거쳐 나온 값인지. */
    @Test fun valuesGoThroughTheFacadeAfterInitialize() {
        initialize()
        assertTrue(OneS1ght.isInitialized)
        assertEquals("AIza_facade", OneS1ght.googleMapKey)
    }

    /** reset 이후 다시 null — 값이 세션에 묶여 있다. */
    @Test fun valuesReturnToNullAfterReset() {
        initialize("ock_facade_probe2")
        assertNotNull("sanity: 초기화가 실제로 값을 채웠는지", OneS1ght.googleMapKey)

        h.await { OneS1ght.reset() }

        assertNull(OneS1ght.googleMapKey)
        assertFalse(OneS1ght.isInitialized)
    }

    /** 고객이 손댈 수 없어야 하는 값은 파사드로 나오지 않는다. */
    @Test fun internalOnlyValuesStayOffTheFacade() {
        initialize("ock_facade_probe3")
        assertNotEquals("gpk_facade", OneS1ght.googleMapKey)
        assertNotEquals("https://space.facade.test", OneS1ght.googleMapKey)
    }

    // MARK: - 생명주기

    /** 같은 키로 다시 부르면 멱등(verify 1회), 다른 키면 세션을 새 키로 다시 만든다. */
    @Test fun sameKeyIsIdempotentAndNewKeyRebuildsSession() {
        initialize("ock_a")
        initialize("ock_a")
        assertEquals(1, h.routes.count("/auth/verify"))

        initialize("ock_b")
        assertEquals(2, h.routes.count("/auth/verify"))
        val lastVerify = h.routes.requests.last { it.path.endsWith("/auth/verify") }
        assertEquals("ock_b", lastVerify.sdkKey)
    }

    /** 초기화 실패여도 세션은 만들어져 있어 재호출이 곧 재시도다. */
    @Test fun failedInitializeCanBeRetried() {
        h.routes.verifyStatus = 401
        assertThrows<ApiError.InvalidKey> { initialize() }
        assertFalse(OneS1ght.isInitialized)

        h.routes.verifyStatus = 200
        initialize()
        assertTrue(OneS1ght.isInitialized)
    }

    /** iOS 동작 그대로 — identify 를 initialize 보다 먼저 부르면 값이 전달되지 않는다. */
    @Test fun identifyBeforeInitializeIsNotCarriedOver() {
        OneS1ght.identify("p-early")
        initialize()
        val session = OneS1ght.floorSession()
        assertThrows<SdkError.NotIdentified> { h.await { session.begin(h.mock) } }
    }

    // MARK: - floorSession

    @Test fun floorSessionThrowsBeforeInitialize() {
        assertThrows<SdkError.NotInitialized> { OneS1ght.floorSession() }
    }

    @Test fun floorSessionIsASingleton() {
        initialize()
        assertSame(OneS1ght.floorSession(), OneS1ght.floorSession())
        h.await { OneS1ght.reset() }
        initialize("ock_other")
        assertSame(FloorSession.shared, OneS1ght.floorSession())
    }

    @Test fun beginWithProviderRunsAndEndStops() {
        initialize()
        OneS1ght.identify("p1")
        val session = OneS1ght.floorSession()
        assertFalse(session.isRunning)
        assertNull(session.floor)

        h.await { session.begin(h.mock) }
        assertTrue(session.isRunning)
        assertTrue(h.mock.isRunning)

        h.await { session.end() }
        assertFalse(session.isRunning)
        assertFalse(h.mock.isRunning)
    }

    /** begin(provider) 는 초기화 전이면 NotInitialized — 기기 게이트는 begin() 에만 있다. */
    @Test fun beginWithProviderBeforeInitializeThrowsNotInitialized() {
        h.capability.supported = false
        assertThrows<SdkError.NotInitialized> { h.await { FloorSession.shared.begin(h.mock) } }
    }

    /** 내장 provider 는 한 번만 만들고 begin/end/begin 에 걸쳐 재사용한다(iOS hub 재사용). */
    @Test fun builtInProviderIsCreatedOnceAndReused() {
        initialize()
        OneS1ght.identify("p1")
        val session = OneS1ght.floorSession()

        h.await { session.begin() }
        h.await { session.end() }
        h.await { session.begin() }

        assertEquals(1, h.builtInCreated)
        assertTrue(h.mock.isRunning)
    }

    /** 내장 provider 의 구역 이벤트가 FloorSession 리스너로 나뉘어 간다(Ruling 1). */
    @Test fun builtInZoneEventsReachSessionListeners() {
        val engine = NoopRangingEngine()
        val hub = UwbPositioningProvider(
            engine,
            ZoneEngine(CoroutineDwellScheduler(CoroutineScope(SupervisorJob() + h.dispatcher))),
            h.dispatcher,
            clock = { 0L },
        )
        h.builtIn = hub
        initialize()
        OneS1ght.identify("p1")
        val session = OneS1ght.floorSession()

        val entered = mutableListOf<String>()
        val exited = mutableListOf<String>()
        val dwelled = mutableListOf<Pair<String, Double>>()
        val logs = mutableListOf<String>()
        session.onZoneEnter = ZoneListener { entered += it.id }
        session.onZoneExit = ZoneListener { exited += it.id }
        session.onZoneDwell = DwellListener { zone, seconds -> dwelled += zone.id to seconds }
        OneS1ght.onDebugLog = DebugLogListener { _, msg -> logs += msg }

        h.await { session.begin() }

        val hook = hub.onZoneEvent
        assertNotNull("begin() 이 구역 훅을 걸어야 한다", hook)
        val zone = Zone("z1", "Z", listOf(Position(0.0, 0.0), Position(1.0, 0.0), Position(1.0, 1.0)))
        hook!!.invoke(ZoneEvent.Enter(zone, 1_000))
        hook.invoke(ZoneEvent.Dwell(zone, 5.0, 6_000))
        hook.invoke(ZoneEvent.Exit(zone, 9_000))

        assertEquals(listOf("z1"), entered)
        assertEquals(listOf("z1"), exited)
        assertEquals(listOf("z1" to 5.0), dwelled)

        val logHook = hub.onLog
        assertNotNull("begin() 이 엔진 로그 훅을 걸어야 한다", logHook)
        logHook!!.invoke(LogLevel.INFO, "engine-line")
        assertTrue(logs.contains("engine-line"))
    }

    /** 코디네이터 훅 → FloorSession·onDebugLog 리스너 배선. */
    @Test fun coordinatorHooksReachListeners() {
        val logs = mutableListOf<Pair<LogLevel, String>>()
        OneS1ght.onDebugLog = DebugLogListener { level, msg -> logs += level to msg }
        initialize()
        assertTrue("초기화 로그가 onDebugLog 로 와야 한다", logs.any { it.second.contains("I1001") })

        OneS1ght.identify("p1")
        val session = OneS1ght.floorSession()
        val positions = mutableListOf<Coordinates>()
        val triggers = mutableListOf<Pair<String, List<Trigger>>>()
        val changes = mutableListOf<ConfigChange>()
        session.onPosition = PositionListener { positions += it }
        session.onTriggers = TriggersListener { zoneId, t -> triggers += zoneId to t }
        session.onConfigChanged = ConfigChangeListener { changes += it }

        h.await { session.begin(h.mock) }
        h.await { h.mock.simulatePosition(Coordinates(1.0, 2.0, 0.0), "f1", 1_000) }
        assertEquals(listOf(Coordinates(1.0, 2.0, 0.0)), positions)

        val coordinator = OneS1ght.coordinatorRef!!
        coordinator.onTriggers!!.invoke("z9", listOf(Trigger("t1", "coupon")))
        assertEquals(listOf("z9" to listOf(Trigger("t1", "coupon"))), triggers)

        // 실시간 스트림도 붙어 있어 재연결 신호(ResyncNeeded)가 섞일 수 있다 — 고유한 값으로 본다.
        val probe = ConfigChange.RulesChanged("z-probe")
        coordinator.deliverConfigChange(probe)
        assertTrue(changes.contains(probe))
    }

    /** pause/resume 은 지금 물린 provider 에 그대로 간다. */
    @Test fun pauseAndResumeGoToActiveProvider() {
        val rec = PausableProvider()
        initialize()
        OneS1ght.identify("p1")
        val session = OneS1ght.floorSession()

        session.pause() // 측위 전 — 아무 일 없음
        assertFalse(session.isPaused)

        h.await { session.begin(rec) }
        session.pause()
        assertTrue(session.isPaused)
        session.resume()
        assertFalse(session.isPaused)
        assertEquals(listOf("pause", "resume"), rec.calls)
    }

    // MARK: - 조회

    /** 콘솔이 측위 키를 못 줬으면 목록은 빈 값, 단건은 NotFound. */
    @Test fun lookupsWithoutSpaceKey() {
        initialize()
        assertEquals(emptyList<Any>(), h.await { OneS1ght.buildings() })
        assertEquals(emptyList<Any>(), h.await { OneS1ght.floors("b1") })
        assertEquals(emptyList<Any>(), h.await { OneS1ght.zones("b1", "f1") })
        assertThrows<ApiError.NotFound> { h.await { OneS1ght.building("b1") } }
        assertThrows<ApiError.NotFound> { h.await { OneS1ght.zone("b1", "f1", "z1") } }
        assertThrows<SdkError.NotInitialized> { h.await { OneS1ght.floor("b1", "f1") } }
        assertEquals(emptyList<Any>(), h.await { OneS1ght.refreshZones() })
        h.await { OneS1ght.setFloorMap(null) }
    }

    /** 초기화 전 조회는 NotInitialized, refreshZones·send 는 조용히 빈 값. */
    @Test fun lookupsBeforeInitialize() {
        assertThrows<SdkError.NotInitialized> { h.await { OneS1ght.buildings() } }
        assertThrows<SdkError.NotInitialized> { h.await { OneS1ght.floors("b") } }
        assertThrows<SdkError.NotInitialized> { h.await { OneS1ght.locators("b", "f") } }
        assertThrows<SdkError.NotInitialized> { h.await { OneS1ght.setFloorMap(null) } }
        assertThrows<SdkError.NotInitialized> { h.await { OneS1ght.createProfile(mapOf("a" to "b")) } }
        assertEquals(emptyList<Any>(), h.await { OneS1ght.refreshZones() })
        h.await { OneS1ght.send() }
        OneS1ght.empty()
    }

    // MARK: - 디스패처 한정 (컨트롤러 지시)

    /**
     * 다른 디스패처(Default)에서 suspend 판을 불러도 코어 상태는 코어 디스패처에서만 바뀐다.
     * 코디네이터가 남기는 로그(onLog → onDebugLog)가 어느 스레드에서 오는지로 본다 —
     * StandardTestDispatcher 의 작업은 runCurrent 를 부르는 테스트 스레드에서만 돈다.
     */
    @Test fun suspendApisRunOnCoreDispatcherWhateverTheCaller() {
        val threads = mutableSetOf<Thread>()
        val offCore = mutableListOf<String>()
        OneS1ght.onDebugLog = DebugLogListener { _, m ->
            synchronized(threads) {
                threads += Thread.currentThread()
                if (Thread.currentThread() != h.coreThread) offCore += m
            }
        }

        h.await(Dispatchers.Default) { OneS1ght.initialize(h.context(), "ock_thread", h.baseUrl()) }
        OneS1ght.identify("p1")
        h.await(Dispatchers.Default) { OneS1ght.floorSession().begin(h.mock) }
        h.await(Dispatchers.Default) { OneS1ght.refreshZones() }
        h.await(Dispatchers.Default) { OneS1ght.floorSession().end() }

        assertTrue("로그가 한 줄도 없으면 검증이 성립하지 않는다", threads.isNotEmpty())
        assertEquals("코어 스레드 밖에서 온 로그: $offCore", setOf(h.coreThread), threads)
    }

    // MARK: - Java 콜백 판

    private class Captured<T> : Callback<T> {
        @Volatile var result: Any? = NONE
        @Volatile var error: Throwable? = null
        @Volatile var thread: Thread? = null

        override fun onSuccess(result: T) {
            this.result = result
            thread = Thread.currentThread()
        }

        override fun onError(error: Throwable) {
            this.error = error
            thread = Thread.currentThread()
        }

        companion object {
            val NONE = Any()
        }
    }

    @Test fun callbackDeliversErrorOnCoreThread() {
        val cb = Captured<List<co.onecheck.ones1ght.android.model.Building>>()
        OneS1ght.buildings(cb)
        h.drain()
        assertTrue(cb.error is SdkError.NotInitialized)
        assertEquals(h.coreThread, cb.thread)
    }

    @Test fun callbackDeliversResultOnCoreThread() {
        val cb = Captured<Void?>()
        OneS1ght.initialize(h.context(), "ock_cb", h.baseUrl(), cb)
        h.drain()
        assertNull(cb.error)
        assertNull(cb.result)
        assertEquals(h.coreThread, cb.thread)
        assertTrue(OneS1ght.isInitialized)

        val profiles = Captured<String>()
        OneS1ght.createProfile(mapOf("g" to "F"), profiles)
        h.drain()
        // 서버가 "{}" 를 주므로 디코딩 실패 — 어느 쪽이든 onError 로 와야 하고 크래시가 없어야 한다.
        assertTrue(profiles.error is ApiError)
    }

    /** 콜백 자체가 던져도 onError 로 다시 들어가지 않는다(성공을 실패로 두 번 알리지 않는다). */
    @Test fun throwingOnSuccessIsNotReportedAsError() {
        var errors = 0
        val cb = object : Callback<Boolean> {
            override fun onSuccess(result: Boolean): Unit = throw IllegalStateException("app bug")
            override fun onError(error: Throwable) {
                errors += 1
            }
        }
        // 앱 코드의 예외는 스레드의 미처리 예외로 올라간다(운영: 메인 스레드 크래시) — 여기서는 받아 둔다.
        val uncaught = mutableListOf<Throwable>()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, e -> uncaught += e }
        try {
            JavaBridge.run(cb) { true }
            h.drain()
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previous)
        }
        assertEquals(0, errors)
        assertEquals(listOf("app bug"), uncaught.map { it.message })
    }

    // MARK: - 기기 판정 (Ruling 10)

    /**
     * Context 를 받기 전에는 칩을 물을 수 없다 — 거짓 AVAILABLE 대신 DEVICE_NOT_SUPPORTED 와
     * WARN 한 번. OS 미달은 Context 없이도 정직하게 답한다. initialize 이후에는 실제 판정.
     */
    @Test fun availabilityBeforeContextIsNotSupportedWithOneWarn() {
        OneS1ght.appContext = null
        val warns = mutableListOf<String>()
        OneS1ght.onDebugLog = DebugLogListener { level, msg -> if (level == LogLevel.WARN) warns += msg }

        assertEquals(DeviceAvailability.DEVICE_NOT_SUPPORTED, OneS1ght.deviceAvailability)
        assertEquals(DeviceAvailability.DEVICE_NOT_SUPPORTED, OneS1ght.deviceAvailability)
        assertFalse(OneS1ght.isDeviceAvailable)
        assertEquals("칩을 묻지 않는다", 0, h.capability.queries)
        assertEquals(listOf("deviceAvailability read before initialize()"), warns)
        assertEquals(PermissionStatus.UNSUPPORTED, h.await { OneS1ght.permissionsWith { PermissionStatus.AUTHORIZED } })

        h.capability.sdkInt = 36
        assertEquals(DeviceAvailability.OS_VERSION_TOO_LOW, OneS1ght.deviceAvailability)
        h.capability.sdkInt = 37

        initialize()
        assertEquals(DeviceAvailability.AVAILABLE, OneS1ght.deviceAvailability)
        assertTrue(h.capability.queries > 0)
    }

    // MARK: - 권한

    @Test fun permissionsUnsupportedDeviceSkipsRequest() {
        h.capability.supported = false
        var asked = 0
        val status = h.await { OneS1ght.permissionsWith { asked += 1; PermissionStatus.AUTHORIZED } }
        assertEquals(PermissionStatus.UNSUPPORTED, status)
        assertEquals(0, asked)

        h.capability.supported = true
        h.capability.sdkInt = 36
        assertEquals(PermissionStatus.UNSUPPORTED, h.await { OneS1ght.permissionsWith { PermissionStatus.AUTHORIZED } })
    }

    @Test fun permissionsSupportedDeviceAsks() {
        assertEquals(PermissionStatus.DENIED, h.await { OneS1ght.permissionsWith { PermissionStatus.DENIED } })
        assertEquals(PermissionStatus.AUTHORIZED, h.await { OneS1ght.permissionsWith { PermissionStatus.AUTHORIZED } })
    }

    @Test fun permissionResultDecision() {
        val ranging = PositioningPermission.RANGING
        val fine = PositioningPermission.FINE_LOCATION
        assertEquals(PermissionStatus.AUTHORIZED, PositioningPermission.decide(mapOf(ranging to true, fine to true)))
        assertEquals(PermissionStatus.DENIED, PositioningPermission.decide(mapOf(ranging to true, fine to false)))
        assertEquals(PermissionStatus.DENIED, PositioningPermission.decide(mapOf(ranging to false, fine to true)))
        // 요청이 취소되면(액티비티 재생성 등) 빈 결과가 온다 — 허용으로 보지 않는다.
        assertEquals(PermissionStatus.DENIED, PositioningPermission.decide(emptyMap()))
        assertEquals(PermissionStatus.DENIED, PositioningPermission.decide(mapOf(ranging to true)))
        assertEquals(listOf("android.permission.RANGING", "android.permission.ACCESS_FINE_LOCATION"), PositioningPermission.PERMISSIONS.toList())
    }

    // MARK: - 가짜

    private class NoopRangingEngine : RangingEngine {
        override fun open(sessionId: Int, listener: RangingEngine.Listener) {}
        override fun close() {}
        override fun applyAnchors(anchors: Map<Int, DoubleArray>) {}
    }

    private class PausableProvider : PositioningProvider {
        override var delegate: PositioningProviderDelegate? = null
        val calls = mutableListOf<String>()
        private var paused = false
        override val isPaused: Boolean get() = paused
        override fun start() {}
        override fun stop() {}
        override fun pause() {
            calls += "pause"
            paused = true
        }
        override fun resume() {
            calls += "resume"
            paused = false
        }
    }
}
