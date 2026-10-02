@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package co.onecheck.ones1ght.android.runtime

//
//  SessionCoordinatorTest.kt
//  라이프사이클 통합 — prepare(초기화)/start(가동) 분리 + Mock 프로바이더 + 스텁 서버.
//  포팅 원본: SessionCoordinatorTests.swift (+ 존 전송 재시도·층 지정·배경 전환 보강).
//

import co.onecheck.ones1ght.android.OneS1ght
import co.onecheck.ones1ght.android.SdkError
import co.onecheck.ones1ght.android.internal.SdkJson
import co.onecheck.ones1ght.android.model.Coordinates
import co.onecheck.ones1ght.android.model.Floor
import co.onecheck.ones1ght.android.model.SdkDefaults
import co.onecheck.ones1ght.android.model.Trigger
import co.onecheck.ones1ght.android.model.ZoneEventStatus
import co.onecheck.ones1ght.android.positioning.MockPositioningProvider
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class SessionCoordinatorTest {

    @get:Rule val server = MockWebServer()

    private val routes = Routes()
    private lateinit var provider: MockPositioningProvider

    @Before
    fun setUp() {
        server.dispatcher = routes
        provider = MockPositioningProvider()
    }

    /** 경로별 canned 응답 (기본 세트) */
    private fun routeDefaults(floorStatus: Int = 200) {
        routes.handler = { path ->
            when {
                path.endsWith("/auth/verify") -> json(VERIFY_OK)
                path.endsWith("/config") -> json("{}")
                path.endsWith("/positioning/buildings") -> json(
                    """
                    { "synced_at": "s", "buildings": [
                      { "building_id": "B1", "name": "금정역 skv1", "store_id": 3,
                        "floors": [ { "floor_id": "F", "name": "F" } ] } ] }
                    """.trimIndent(),
                )
                path.contains("/positioning/floors/") ->
                    if (floorStatus == 404) {
                        json("""{ "detail": "no zones" }""", 404)
                    } else {
                        json(
                            """
                            { "floor_id": "F", "building_id": null, "name": "F", "synced_at": "s",
                              "zones": [], "anchors": [] }
                            """.trimIndent(),
                        )
                    }
                path.endsWith("/events/zone") -> json(ZONE_OK)
                path.endsWith("/positioning/logs") -> json("""{ "accepted_count": 99 }""")
                path.endsWith("/logs") -> json("""{ "accepted_count": 1 }""")
                else -> MockResponse().setResponseCode(500)
            }
        }
    }

    private fun TestScope.coordinator(flushThreshold: Int = 100, lifecycle: AppLifecycle? = null) =
        makeCoordinator(server, flushThreshold = flushThreshold, lifecycle = lifecycle)

    /** prepare + start 한 번에 (개별 단계는 아래 전용 테스트에서) */
    private suspend fun TestScope.makeStarted(
        flushThreshold: Int = 100,
        lifecycle: AppLifecycle? = null,
    ): SessionCoordinator {
        val c = coordinator(flushThreshold, lifecycle)
        c.prepare()
        c.identify("pf_8a3c")
        c.start(provider)
        return c
    }

    private fun body(suffix: String): JsonObject {
        val rec = routes.requests.first { it.path.endsWith(suffix) }
        return SdkJson.parseToJsonElement(rec.body).jsonObject
    }

    // prepare: verify 만. "세션 가능" 확정, 측위는 아직 안 돎.
    @Test fun prepare_verifiesKeyOnly() = runTest {
        routeDefaults()
        val c = coordinator()
        c.prepare()

        assertTrue(c.isPrepared)
        assertFalse(provider.isRunning)
        // buildings 는 부르지 않는다 — /config 는 prepare() 가 키 해석을 위해 더한 호출
        assertEquals(listOf("/api/sdk/v1/auth/verify", "/api/sdk/v1/config"), routes.paths())
        // verify 는 키 검증만 — 클라이언트 정보를 싣지 않는다
        val verify = body("/auth/verify")
        assertNull(verify["client"])
        assertEquals("Android", verify["platform_name"]?.jsonPrimitive?.content)
        assertEquals("co.onecheck.test", verify["app_id"]?.jsonPrimitive?.content)
    }

    // 멱등 — 두 번째 prepare 는 아무 요청도 내지 않는다.
    @Test fun prepare_isIdempotent() = runTest {
        routeDefaults()
        val c = coordinator()
        c.prepare()
        val before = routes.requests.size
        c.prepare()
        assertEquals(before, routes.requests.size)
    }

    // start: provider 가동. consent 가 사라져 verify 재호출도 없어졌다.
    @Test fun start_afterPrepare_runsWithoutExtraVerify() = runTest {
        routeDefaults()
        val c = makeStarted()

        assertTrue(provider.isRunning)
        assertTrue(c.visitorId.startsWith("v-"))
        assertEquals("v-20260928-001", c.visitorId)
        // 층 미선택 상태 — SDK 가 임의로 건물·층을 고르지 않는다
        assertNull(provider.appliedBuildingId)
        assertNull(provider.appliedFloorId)
        assertEquals(1, routes.count("/auth/verify"))
        c.stop()
    }

    // prepare 없이 start → notInitialized
    @Test fun start_withoutPrepare_throwsNotInitialized() = runTest {
        routeDefaults()
        val c = coordinator()
        try {
            c.start(provider)
            fail("notInitialized여야 함")
        } catch (e: SdkError) {
            assertEquals(SdkError.NotInitialized(), e)
        }
        assertFalse(provider.isRunning)
    }

    // 인증 게이팅 — identify 없이 start: 수집 미시작 (서버 요청도 안 나감)
    @Test fun start_withoutIdentify_doesNotStartCollection() = runTest {
        routeDefaults()
        val c = coordinator()
        c.prepare()
        val requestsAfterPrepare = routes.requests.size

        try {
            c.start(provider)
            fail("notIdentified여야 함")
        } catch (e: SdkError) {
            assertEquals(SdkError.NotIdentified(), e)
        }

        assertFalse(provider.isRunning)
        assertFalse(c.isRunning)
        never { routes.requests.size != requestsAfterPrepare }
    }

    // positioning_enabled=false → prepare부터 실패 (세션 불가를 미리 앎)
    @Test fun prepare_positioningDisabled() = runTest {
        routes.handler = { json("""{ "valid": true, "tenant_code": "t", "positioning_enabled": false }""") }
        val c = coordinator()
        try {
            c.prepare()
            fail("positioningDisabled여야 함")
        } catch (e: SdkError) {
            assertEquals(SdkError.PositioningDisabled(), e)
        }
        assertFalse(c.isPrepared)
    }

    // positioning_enabled=false 라도 관련 키(Google Maps 등)는 받아 둬야 한다 (I5, §3.2)
    @Test fun positioningDisabledStillStoresConsoleKeys() = runTest {
        routes.handler = { path ->
            when {
                path.endsWith("/auth/verify") ->
                    json("""{ "valid": true, "tenant_code": "t", "positioning_enabled": false }""")
                path.endsWith("/config") ->
                    json("""{ "google_map_key": "AIza_disabled", "geo_partner_key": "gpk_disabled" }""")
                else -> json("{}")
            }
        }
        val c = coordinator()
        try {
            c.prepare()
            fail("positioningDisabled여야 함")
        } catch (e: SdkError) {
            assertEquals(SdkError.PositioningDisabled(), e)
        }

        assertEquals("측위가 꺼져도 지도 키는 받아야 한다", "AIza_disabled", c.googleMapKey)
    }

    // 존 판정 → events/zone 전송 (바디 검증) → triggers 호스트 콜백
    @Test fun zoneEvent_sendsAndDeliversTriggers() = runTest {
        routeDefaults()
        val c = makeStarted()

        val got = mutableListOf<Pair<String, List<Trigger>>>()
        c.onTriggers = { zoneId, triggers -> got.add(zoneId to triggers) }
        provider.simulateZone("Z1", ZoneEventStatus.ENTER, "F", BASE_MS)
        eventually { got.isNotEmpty() }

        assertEquals("Z1", got[0].first)
        assertEquals("무료커피", got[0].second.first().payload?.get("title"))
        val zone = body("/events/zone")
        assertEquals("Z1", zone["zone_id"]?.jsonPrimitive?.content)
        assertEquals("IN", zone["status"]?.jsonPrimitive?.content)
        assertEquals(c.visitorId, zone["visitor_id"]?.jsonPrimitive?.content)
        assertEquals("pf_8a3c", zone["profile_id"]?.jsonPrimitive?.content)
        assertEquals("F", zone["floor_id"]?.jsonPrimitive?.content)
        assertEquals("Android", zone["platform_name"]?.jsonPrimitive?.content)
        assertEquals("2026-09-28T00:00:00.000Z", zone["occurred_at"]?.jsonPrimitive?.content)
        c.stop()
    }

    // 소량 재시도 (사양서 §9) — network 실패만 1회. 1회차 연결 끊김, 2회차 200 → 콜백 1회.
    @Test fun zoneEventRetriesOnceOnNetworkError() = runTest {
        routeDefaults()
        val zoneCalls = java.util.concurrent.atomic.AtomicInteger()
        val base = routes.handler
        routes.handler = { path ->
            if (path.endsWith("/events/zone") && zoneCalls.incrementAndGet() == 1) {
                // DISCONNECT_AT_START 는 Dispatcher.dispatch() 에서 돌려주면 무시된다(peek 전용) —
                // 요청을 읽은 뒤 끊는 AFTER_REQUEST 로 같은 "전송 실패" 를 만든다.
                MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)
            } else {
                base(path)
            }
        }
        val c = makeStarted()
        val lines = mutableListOf<String>()
        c.onLog = { _, line -> lines.add(line) }
        var delivered = 0
        c.onTriggers = { _, _ -> delivered += 1 }

        provider.simulateZone("Z1", ZoneEventStatus.ENTER, "F", BASE_MS)
        eventually { delivered > 0 }
        never { delivered > 1 }

        assertEquals(1, delivered)
        assertEquals(2, routes.count("/events/zone"))
        assertTrue(lines.toString(), lines.any { it == SdkLocalized.t("coord.zoneRetryOK", "IN") })
        c.stop()
    }

    // 재시도까지 실패하면 드랍 — E5001 로 남기고 문맥에 무엇을 버렸는지 적는다.
    @Test fun zoneEventDroppedAfterSecondNetworkFailure() = runTest {
        routeDefaults()
        val base = routes.handler
        routes.handler = { path ->
            if (path.endsWith("/events/zone")) {
                MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)
            } else {
                base(path)
            }
        }
        val c = makeStarted()
        val lines = mutableListOf<String>()
        c.onLog = { _, line -> lines.add(line) }
        var delivered = 0
        c.onTriggers = { _, _ -> delivered += 1 }

        provider.simulateZone("Z9", ZoneEventStatus.EXIT, "F", BASE_MS)
        eventually { lines.any { it.startsWith("[E5001]") } }

        assertEquals(0, delivered)
        assertEquals("1회만 재시도한다", 2, routes.count("/events/zone"))
        assertTrue(lines.toString(), lines.any { it.contains("zone=Z9 status=OUT dropped") })
        c.stop()
    }

    // 서버 오류는 재시도하지 않는다 — 코드로 옮겨 남기고 드랍.
    @Test fun zoneEventServerErrorIsNotRetried() = runTest {
        routeDefaults()
        val base = routes.handler
        routes.handler = { path -> if (path.endsWith("/events/zone")) json("{}", 500) else base(path) }
        val c = makeStarted()
        val lines = mutableListOf<String>()
        c.onLog = { _, line -> lines.add(line) }

        provider.simulateZone("Z1", ZoneEventStatus.ENTER, "F", BASE_MS)
        eventually { lines.any { it.startsWith("[E5002]") } }

        assertEquals(1, routes.count("/events/zone"))
        assertTrue(lines.toString(), lines.any { it.contains("zone=Z1 status=IN dropped") })
        c.stop()
    }

    // DWELL 은 앱 콜백 전용 — 서버로 가지 않는다(사양서 §6).
    @Test fun dwellNeverReachesServer() = runTest {
        routeDefaults()
        val c = makeStarted()
        provider.simulateZone("Z1", ZoneEventStatus.DWELL, "F", BASE_MS)
        never { routes.count("/events/zone") > 0 }
        c.stop()
    }

    // 좌표: 임계(2건) 도달 시 자동 벌크 전송 (봉투 검증)
    @Test fun positions_bufferAndAutoFlushAtThreshold() = runTest {
        routeDefaults()
        val c = makeStarted(flushThreshold = 2)

        // capturedAt 을 1초 간격으로 — 기본 4Hz(=0.25초) 다운샘플을 둘 다 통과한다.
        provider.simulatePosition(Coordinates(1.0, 2.0, 0.0), "F", BASE_MS)
        provider.simulatePosition(Coordinates(3.0, 4.0, 0.0), "F", BASE_MS + 1_000)

        eventually { routes.count("/positioning/logs") > 0 }

        val bulk = body("/positioning/logs")
        val points = bulk["points"]!!.jsonArray
        assertEquals(2, points.size)
        val first = points[0].jsonObject
        assertEquals("""{"x":1.0,"y":2.0,"z":0.0}""", first["coordinates"].toString())
        assertEquals("2026-09-28T00:00:00.000Z", first["captured_at"]?.jsonPrimitive?.content)
        assertEquals(c.visitorId, bulk["visitor_id"]?.jsonPrimitive?.content)
        assertEquals("pf_8a3c", bulk["profile_id"]?.jsonPrimitive?.content)
        assertEquals("Android", bulk["platform_name"]?.jsonPrimitive?.content)
        c.stop()
    }

    // 좌표 콜백은 원속도 — 다운샘플과 무관하게 전부 앱에 간다.
    @Test fun onPositionIsNotDownsampled() = runTest {
        routeDefaults()
        val c = makeStarted(flushThreshold = 1_000)
        var seen = 0
        c.onPosition = { seen += 1 }
        for (i in 0 until 20) {
            provider.simulatePosition(Coordinates(i.toDouble(), 0.0, 0.0), "F", BASE_MS + i * 50L)
        }
        assertEquals(20, seen)
        c.stop()
    }

    // stop → 잔여 좌표 flush. 초기화 상태는 유지 (재시작 가능)
    @Test fun stop_flushesRemainder_staysPrepared() = runTest {
        routeDefaults()
        val c = makeStarted(flushThreshold = 100)
        provider.simulatePosition(Coordinates(9.0, 9.0, 0.0), "F", BASE_MS)

        c.stop()

        assertTrue(routes.count("/positioning/logs") > 0)
        assertEquals(0, c.pendingCount)
        assertFalse(provider.isRunning)
        assertFalse(c.isRunning)
        assertTrue(c.isPrepared) // 초기화는 살아있음 → start 재호출 가능
    }

    // stop 때 flush 가 실패하면 유실을 E5006 으로 남긴다.
    @Test fun stop_reportsPendingDroppedWhenFlushFails() = runTest {
        routeDefaults()
        val base = routes.handler
        routes.handler = { path -> if (path.endsWith("/positioning/logs")) json("{}", 500) else base(path) }
        val c = makeStarted(flushThreshold = 100)
        val lines = mutableListOf<String>()
        c.onLog = { _, line -> lines.add(line) }
        provider.simulatePosition(Coordinates(9.0, 9.0, 0.0), "F", BASE_MS)

        c.stop()

        assertEquals(1, c.pendingCount)
        assertTrue(lines.toString(), lines.any { it.startsWith("[E5006]") && it.endsWith("points=1") })
        assertTrue(lines.toString(), lines.any { it.startsWith("[I4002]") })
    }

    // 60초 타이머 — 측위 중에만 돈다.
    @Test fun flushTimerSendsEvery60Seconds() = runTest {
        routeDefaults()
        val c = makeStarted(flushThreshold = 1_000)
        provider.simulatePosition(Coordinates(1.0, 1.0, 0.0), "F", BASE_MS)
        advanceTimeBy(59_000)
        runCurrent()
        never(100) { routes.count("/positioning/logs") > 0 }
        advanceTimeBy(1_001)
        eventually { routes.count("/positioning/logs") == 1 }
        c.stop()
    }

    // send() / empty()
    @Test fun flushNowAndEmpty() = runTest {
        routeDefaults()
        val c = makeStarted(flushThreshold = 1_000)
        provider.simulatePosition(Coordinates(1.0, 1.0, 0.0), "F", BASE_MS)
        c.flush()
        assertEquals(1, routes.count("/positioning/logs"))
        provider.simulatePosition(Coordinates(2.0, 1.0, 0.0), "F", BASE_MS + 1_000)
        assertEquals(1, c.pendingCount)
        c.empty()
        assertEquals(0, c.pendingCount)
        c.stop()
        assertEquals("비운 좌표는 stop 때도 안 간다", 1, routes.count("/positioning/logs"))
    }

    // MARK: - position_rate_hz 다운샘플

    // 기본 4Hz — 0.05초 간격으로 20개를 넣어도 서버로 가는 건 솎인 소수뿐.
    @Test fun downsampleAt4HzKeeps4to6of20() = runTest {
        routeDefaults()
        val c = makeStarted(flushThreshold = 1_000) // flush 안 나게 크게

        for (i in 0 until 20) {
            provider.simulatePosition(Coordinates(i.toDouble(), 0.0, 0.0), "F", BASE_MS + i * 50L)
        }
        // 1초 구간에 4Hz → 최대 5개 안팎 (경계 여유 10% 포함)
        assertTrue("${c.pendingCount}", c.pendingCount <= 6)
        assertTrue("${c.pendingCount}", c.pendingCount >= 4)
        c.stop()
    }

    // 서버가 내려준 rate 를 반영한다 — 20Hz 면 0.05초 간격이 전부 통과.
    @Test fun positionRate_serverValueApplied() = runTest {
        routes.handler = { path ->
            when {
                path.endsWith("/auth/verify") -> json(
                    """{ "valid": true, "tenant_code": "t", "positioning_enabled": true, "position_rate_hz": 20 }""",
                )
                path.contains("/positioning/floors/") -> json(
                    """{ "floor_id": "F", "building_id": null, "name": "F", "synced_at": "s", "zones": [], "anchors": [] }""",
                )
                else -> json("""{ "accepted_count": 0 }""")
            }
        }
        val c = makeStarted(flushThreshold = 1_000)
        assertEquals(20, c.positionRateHz)

        for (i in 0 until 10) {
            provider.simulatePosition(Coordinates(i.toDouble(), 0.0, 0.0), "F", BASE_MS + i * 50L)
        }
        assertEquals(10, c.pendingCount) // 전부 통과
        c.stop()
    }

    // 범위 밖 값은 1~100 으로 접는다 (서버가 접어 보내지만 SDK 도 방어).
    @Test fun rateClamp9999To100() = runTest {
        routes.handler = { path ->
            if (path.endsWith("/auth/verify")) {
                json("""{ "valid": true, "tenant_code": "t", "positioning_enabled": true, "position_rate_hz": 9999 }""")
            } else {
                json("""{ "accepted_count": 0 }""")
            }
        }
        val c = coordinator()
        c.prepare()
        assertEquals(SdkDefaults.MAX_RATE_HZ, c.positionRateHz)
        assertEquals(100, c.positionRateHz)
    }

    @Test fun rateClampZeroTo1() = runTest {
        routes.handler = { path ->
            if (path.endsWith("/auth/verify")) {
                json("""{ "valid": true, "tenant_code": "t", "positioning_enabled": true, "position_rate_hz": 0 }""")
            } else {
                json("{}")
            }
        }
        val c = coordinator()
        c.prepare()
        assertEquals(SdkDefaults.MIN_RATE_HZ, c.positionRateHz)
    }

    // MARK: - 층 지정 (setFloorMap)

    private fun routeWithFloor(anchorsFail: Boolean = false, zonesJson: String = ZONES_A) {
        routes.handler = { path ->
            when {
                path.endsWith("/auth/verify") -> json(VERIFY_OK)
                path.endsWith("/config") -> json("""{ "geo_sdk_key": "gsk_console" }""")
                path.endsWith("/floor/F/plan") -> json("""{"has_plan":false,"floor_name":"F","plan":null}""")
                path.endsWith("/floor/F/zones") -> json(zonesJson)
                path.endsWith("/anchors") ->
                    if (anchorsFail) {
                        MockResponse().setResponseCode(500)
                    } else {
                        json(
                            """
                            {"anchors":[
                              {"uwbMac":"AABBCCDD9DD7","x":1.0,"y":2.0,"sessionId":4444,"clusterStatus":"auto_done"},
                              {"uwbMac":"AABBCCDD9DD8","x":8.0,"y":2.0,"sessionId":4444,"clusterStatus":"auto_done"}
                            ]}
                            """.trimIndent(),
                        )
                    }
                path.endsWith("/logs") -> json("""{ "accepted_count": 1 }""")
                else -> json("{}", 404)
            }
        }
    }

    @Test fun setFloorMap_reportsFloorSetWithCounts() = runTest {
        routeWithFloor()
        val c = coordinator()
        c.prepare()
        val lines = mutableListOf<String>()
        c.onLog = { _, line -> lines.add(line) }

        c.setFloorMap(Floor("F", "F"), "B")

        assertEquals("F", c.currentFloor?.id)
        assertEquals("B", c.currentBuildingId)
        assertEquals(2, c.floorState?.locators?.size)
        assertTrue(
            lines.toString(),
            lines.any { it.startsWith("[I3001]") && it.endsWith("building=B floor=F locators=2 zones=1") },
        )
        assertTrue("도면 없음은 I3002", lines.any { it.startsWith("[I3002]") && it.endsWith("floor=F") })
        assertFalse(lines.any { it.startsWith("[E3006]") || it.startsWith("[E3002]") })
    }

    @Test fun setFloorMap_locatorFetchFailureIsE3006NotE3002() = runTest {
        routeWithFloor(anchorsFail = true)
        val c = coordinator()
        c.prepare()
        val lines = mutableListOf<String>()
        c.onLog = { _, line -> lines.add(line) }

        c.setFloorMap(Floor("F", "F"), "B")

        assertNotNull("층은 열린다", c.floorState)
        assertTrue(lines.toString(), lines.any { it.startsWith("[E3006]") && it.endsWith("floor=F") })
        assertFalse("못 받은 것과 안 깐 것을 섞지 않는다", lines.any { it.startsWith("[E3002]") })
        assertTrue(lines.any { it.startsWith("[E3003]") })
    }

    @Test fun setFloorMap_withoutSpaceKeyThrowsNotInitialized() = runTest {
        routeDefaults() // /config 가 {} — 공간 서비스 키 없음
        val c = coordinator()
        c.prepare()
        try {
            c.setFloorMap(Floor("F", "F"), "B")
            fail("notInitialized여야 함")
        } catch (e: SdkError) {
            assertEquals(SdkError.NotInitialized(), e)
        }
    }

    @Test fun setFloorMap_whileRunningAppliesToProvider_andNilClears() = runTest {
        routeWithFloor()
        val c = coordinator()
        c.prepare()
        c.identify("pf")
        c.start(provider)
        assertNull(provider.appliedFloorId)

        c.setFloorMap(Floor("F", "F"), "B")
        assertEquals("F", provider.appliedFloorId)
        assertEquals("B", provider.appliedBuildingId)
        assertEquals(4444, provider.appliedConfig?.sessionId)
        assertEquals(setOf(0x9DD7, 0x9DD8), provider.appliedConfig?.anchors?.keys)
        assertEquals(1, provider.appliedConfig?.zones?.size)

        c.setFloorMap(null, null)
        assertNull(c.floorState)
        assertNull(c.currentFloor)
        assertEquals("층을 비우면 엔진 설정도 비운다", PositioningConfigEmpty, provider.appliedConfig)
        // 콘솔 층 ID 도 비운다 — 안 그러면 provider 가 옛 층으로 엔진 층을 대조(E3008)하고 이벤트를 귀속한다.
        assertEquals("", provider.appliedFloorId)
        assertEquals("", provider.appliedBuildingId)
        c.stop()
    }

    @Test fun start_withFloorSetAppliesFloorState() = runTest {
        routeWithFloor()
        val c = coordinator()
        c.prepare()
        c.identify("pf")
        c.setFloorMap(Floor("F", "F"), "B")
        val lines = mutableListOf<String>()
        c.onLog = { _, line -> lines.add(line) }
        c.start(provider)
        assertEquals("F", provider.appliedFloorId)
        assertFalse("층이 있으면 E3001 없음", lines.any { it.startsWith("[E3001]") })
        c.stop()
    }

    /** 층 없이 시작 — 정상 경로라 화면 로그(INFO)만, E3001 은 올리지 않는다(iOS #54). */
    @Test fun start_withoutFloorLogsInfoNotE3001() = runTest {
        routeDefaults()
        val c = coordinator()
        c.prepare()
        c.identify("pf")
        val lines = mutableListOf<Pair<LogLevel, String>>()
        c.onLog = { level, line -> lines.add(level to line) }
        c.start(provider)
        assertTrue(lines.toString(), lines.any { it.first == LogLevel.INFO && it.second == SdkLocalized.t("coord.noFloorLoaded") })
        assertFalse(lines.toString(), lines.any { it.second.startsWith("[E3001]") })
        assertTrue(lines.toString(), lines.any { it.second.startsWith("[I4001]") && it.second.endsWith("visitor=v-20260928-001") })
        c.stop()
    }

    // MARK: - 존 재조회

    @Test fun refreshZones_withoutFloorReturnsEmpty() = runTest {
        routeDefaults()
        val c = coordinator()
        c.prepare()
        assertEquals(emptyList<Any>(), c.refreshZones())
    }

    @Test fun refreshZones_reloadsGeofencesOnlyWhenIdsChange() = runTest {
        routeWithFloor()
        val c = coordinator()
        c.prepare()
        c.identify("pf")
        c.setFloorMap(Floor("F", "F"), "B")
        c.start(provider)

        val appliedBefore = provider.appliedConfig
        assertEquals(listOf("z-1"), c.refreshZones().map { it.id })
        assertEquals("같은 구역이면 엔진을 건드리지 않는다", 0, provider.reloadGeofencesCount)
        // 감사 SP-B2 — 같은 구역이면 provider 에 다시 물리지도 않는다(판정기 체류 상태를 지우던 경로).
        assertTrue("같은 구역을 provider 에 다시 물렸다", provider.appliedConfig === appliedBefore)

        routeWithFloor(zonesJson = ZONES_AB)
        assertEquals(listOf("z-1", "z-2"), c.refreshZones().map { it.id })
        assertEquals(1, provider.reloadGeofencesCount)
        assertEquals(2, provider.appliedConfig?.zones?.size)
        assertEquals(2, c.floorState?.zones?.size)
        c.stop()
    }

    @Test fun refreshZones_failureKeepsCurrentZones() = runTest {
        routeWithFloor()
        val c = coordinator()
        c.prepare()
        c.setFloorMap(Floor("F", "F"), "B")
        val base = routes.handler
        routes.handler = { path -> if (path.endsWith("/zones")) json("{}", 500) else base(path) }
        assertEquals(listOf("z-1"), c.refreshZones().map { it.id })
    }

    // MARK: - 배경 전환

    @Test fun backgroundStopsProviderAndFlushes_foregroundRestarts() = runTest {
        routeDefaults()
        val lifecycle = FakeAppLifecycle()
        val c = makeStarted(flushThreshold = 1_000, lifecycle = lifecycle)
        assertTrue(lifecycle.isObserving)
        provider.simulatePosition(Coordinates(1.0, 1.0, 0.0), "F", BASE_MS)

        lifecycle.background()
        eventually { routes.count("/positioning/logs") == 1 }
        assertFalse(provider.isRunning)
        assertTrue("세션은 살아 있다", c.isRunning)

        lifecycle.foreground()
        runCurrent()
        assertTrue(provider.isRunning)
        c.stop()
        assertFalse("층도 측위도 없으면 관찰을 푼다", lifecycle.isObserving)
    }

    // MARK: - 로그 채널

    @Test fun reportGoesToOnLogAndServerLogBuffer() = runTest {
        routeDefaults()
        val c = makeStarted()
        val lines = mutableListOf<Pair<LogLevel, String>>()
        c.onLog = { level, line -> lines.add(level to line) }

        c.report(SdkErrorCode.UWB_SESSION_FAILED, "ctx=1")

        // 등급은 코드의 세기(E4001 = ERROR), 문구는 현재 언어의 코드 요약(감사 K9 · SP-C8)
        assertEquals(LogLevel.ERROR to "[E4001] ${localizedSummary(SdkErrorCode.UWB_SESSION_FAILED)} — ctx=1", lines.last())
        // ERROR 는 즉시 flush — /logs 로 코드와 문맥만 간다(요약 문구는 안 간다)
        eventually { routes.count("/logs") - routes.count("/positioning/logs") > 0 }
        val logs = routes.requests.first { it.path == "/api/sdk/v1/logs" }
        val obj = SdkJson.parseToJsonElement(logs.body).jsonObject
        assertEquals("Android", obj["platform_name"]?.jsonPrimitive?.content)
        assertEquals(OneS1ght.SDK_VERSION, obj["sdk_version"]?.jsonPrimitive?.content)
        val entry = obj["entries"]!!.jsonArray.map { it.jsonObject }.first { it["code"]?.jsonPrimitive?.content == "E4001" }
        assertEquals("ERROR", entry["level"]?.jsonPrimitive?.content)
        assertEquals("ctx=1", entry["message"]?.jsonPrimitive?.content)
        assertFalse(logs.body.contains("UWB 세션 실패"))
        c.stop()
    }

    @Test fun providerReportIsForwarded() = runTest {
        routeDefaults()
        val c = makeStarted()
        val lines = mutableListOf<String>()
        c.onLog = { _, line -> lines.add(line) }
        c.onReport(provider, SdkErrorCode.ZONE_MAPPING_FAILED, "name=x")
        assertEquals("[E3009] 영역 이름에 맞는 콘솔 존 없음 — name=x", lines.last())
        c.stop()
    }

    /** Bluetooth 꺼짐은 E2004(WARN)로 onLog 에 남는다 — E2003(권한 거부)으로 뭉개지지 않는다. */
    @Test fun bluetoothOffReportIsForwardedAsE2004() = runTest {
        routeDefaults()
        val c = makeStarted()
        val lines = mutableListOf<String>()
        c.onLog = { _, line -> lines.add(line) }
        c.onReport(provider, SdkErrorCode.BLUETOOTH_OFF, "engine=3 bluetooth unavailable: powered off")
        assertEquals("[E2004] Bluetooth 꺼짐 — engine=3 bluetooth unavailable: powered off", lines.last())
        assertEquals(SdkLogLevel.WARN, SdkErrorCode.BLUETOOTH_OFF.level)
        c.stop()
    }

    private companion object {
        val PositioningConfigEmpty = co.onecheck.ones1ght.android.positioning.PositioningConfig()

        const val ZONE_OK = """
            { "accepted": true, "event_id": "e1",
              "triggers": [ { "trigger_id": "a1", "type": "coupon", "payload": { "title": "무료커피" } } ] }
        """

        const val ZONES_A = """{"zones":[{"zone_id":"z-1","name":"A","is_active":true,
            "polygon":[[0.0,0.0],[5.0,0.0],[5.0,4.0],[0.0,4.0]]}]}"""

        const val ZONES_AB = """{"zones":[{"zone_id":"z-1","name":"A","is_active":true,
            "polygon":[[0.0,0.0],[5.0,0.0],[5.0,4.0],[0.0,4.0]]},
            {"zone_id":"z-2","name":"B","is_active":true,
            "polygon":[[6.0,0.0],[9.0,0.0],[9.0,4.0],[6.0,4.0]]}]}"""
    }
}
