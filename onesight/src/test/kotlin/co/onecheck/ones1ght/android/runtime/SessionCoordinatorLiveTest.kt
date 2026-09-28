@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package co.onecheck.ones1ght.android.runtime

//
//  SessionCoordinatorLiveTest.kt
//  SDK 는 신호를 전달만 한다 — 존을 대신 다시 받지 않는다.
//  포팅 원본: SessionCoordinatorLiveTests.swift (SessionCoordinatorLiveTests +
//  SessionCoordinatorStreamLifetimeTests) + 스트림 붙임·뗌 배선 보강.
//

import co.onecheck.ones1ght.android.model.ConfigChange
import co.onecheck.ones1ght.android.model.Floor
import co.onecheck.ones1ght.android.model.FloorState
import co.onecheck.ones1ght.android.positioning.MockPositioningProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class SessionCoordinatorLiveTest {

    @get:Rule val server = MockWebServer()

    private val routes = Routes()

    @Before
    fun setUp() {
        server.dispatcher = routes
        routes.handler = { path ->
            when {
                path.endsWith("/auth/verify") -> json(VERIFY_OK)
                path.endsWith("/config") -> json("""{ "geo_sdk_key": "gsk_console" }""")
                path.endsWith("/plan") -> json("""{"has_plan":false,"floor_name":"F","plan":null}""")
                path.endsWith("/zones") -> json("""{"zones":[]}""")
                path.endsWith("/anchors") -> json("""{"anchors":[]}""")
                else -> json("""{ "accepted_count": 0 }""")
            }
        }
    }

    @Test fun configChangeIsForwardedUntouched() = runTest {
        val coord = makeCoordinator(server)
        val got = mutableListOf<ConfigChange>()
        coord.onConfigChange = { got.add(it) }

        coord.deliverConfigChange(ConfigChange.ZonesChanged("f-1"))
        coord.deliverConfigChange(ConfigChange.ResyncNeeded)
        coord.deliverConfigChange(ConfigChange.RulesChanged("88"))

        assertEquals(
            listOf(ConfigChange.ZonesChanged("f-1"), ConfigChange.ResyncNeeded, ConfigChange.RulesChanged("88")),
            got,
        )
    }

    // MARK: - 층 전환 시 스트림 재구독 판정

    private fun floorState(building: String, floor: String, sessionId: Int? = null) =
        FloorState(building, floor, sessionId, emptyList(), emptyList(), hasPlan = true, locatorsFetchFailed = false)

    @Test fun floorFilterUnchangedWhenNothingSet() {
        assertFalse(SessionCoordinator.floorFilterChanged(null, null))
    }

    @Test fun floorFilterChangesFromNilToFloor() {
        assertTrue(SessionCoordinator.floorFilterChanged(null, floorState("B1", "F1")))
    }

    // setFloorMap(nil, ...) — 층을 비우는 것도 "바뀜"이다(테넌트 전체 필터로 계속 받아야 한다).
    @Test fun floorFilterChangesFromFloorToNil() {
        assertTrue(SessionCoordinator.floorFilterChanged(floorState("B1", "F1"), null))
    }

    @Test fun floorFilterChangesBetweenTwoDifferentFloors() {
        assertTrue(SessionCoordinator.floorFilterChanged(floorState("B1", "F1"), floorState("B1", "F2")))
    }

    // 같은 층이면 다른 필드(sessionId 등)가 달라져도 재구독하지 않는다.
    @Test fun floorFilterUnchangedWhenSameFloorReset() {
        assertFalse(SessionCoordinator.floorFilterChanged(floorState("B1", "F1", null), floorState("B1", "F1", 42)))
    }

    // MARK: - 스트림을 언제 붙여 둘 것인가 (SessionCoordinatorStreamLifetimeTests)

    /** 이번 수정의 핵심 — 측위를 시작하지 않아도 층만 정해지면 붙는다. */
    @Test fun streamWantedWhenFloorSetWithoutSession() {
        assertTrue(SessionCoordinator.streamWanted(floorSet = true, running = false))
    }

    /** 층이 아직 없어도 측위가 돌면 붙는다. */
    @Test fun streamWantedWhileRunningWithoutFloor() {
        assertTrue(SessionCoordinator.streamWanted(floorSet = false, running = true))
    }

    /** 둘 다 아니면 붙이지 않는다. */
    @Test fun streamNotWantedWhenIdle() {
        assertFalse(SessionCoordinator.streamWanted(floorSet = false, running = false))
    }

    // MARK: - 배선 — 실제로 언제 붙이고 떼는가 (네트워크 없이: 죽은 스코프 위의 스트림)

    /** 만들어진 스트림을 센다. 죽은 스코프라 start() 가 네트워크로 나가지 않는다. */
    private class StreamSpy {
        val created = mutableListOf<LiveConfigStream>()
        val deadScope = CoroutineScope(Job().apply { cancel() })
        var onChange: ((ConfigChange) -> Unit)? = null

        fun factory(): ((ConfigChange) -> Unit, (LogLevel, String) -> Unit) -> LiveConfigStream? = { ch, lg ->
            onChange = ch
            LiveConfigStream(OkHttpClient(), "http://127.0.0.1:1/api/sdk/v1", "k", deadScope, ch, lg).also { created.add(it) }
        }
    }

    @Test fun idleCoordinatorHasNoStream() = runTest {
        val spy = StreamSpy()
        val c = makeCoordinator(server, liveFactory = spy.factory())
        c.prepare()
        assertEquals(0, spy.created.size)
        assertFalse(c.liveStreamWanted)
    }

    @Test fun floorSetAttachesOnce_sameFloorDoesNotReattach_otherFloorDoes() = runTest {
        val spy = StreamSpy()
        val c = makeCoordinator(server, liveFactory = spy.factory())
        c.prepare()

        c.setFloorMap(Floor("F1", "F1"), "B")
        assertEquals(1, spy.created.size)
        c.setFloorMap(Floor("F1", "F1"), "B")
        assertEquals("같은 층이면 재연결하지 않는다", 1, spy.created.size)
        c.setFloorMap(Floor("F2", "F2"), "B")
        assertEquals(2, spy.created.size)
        c.setFloorMap(null, null)
        assertEquals("층을 비우면 테넌트 전체 필터로 다시 붙는다 — 아니면 멈춘다", 2, spy.created.size)
        assertFalse(c.liveStreamWanted)
    }

    @Test fun startAttaches_stopWithoutFloorDetaches_stopWithFloorKeeps() = runTest {
        val spy = StreamSpy()
        val lifecycle = FakeAppLifecycle()
        val c = makeCoordinator(server, lifecycle = lifecycle, liveFactory = spy.factory())
        c.prepare()
        c.identify("pf")
        val p = MockPositioningProvider()

        c.start(p)
        assertEquals(1, spy.created.size)
        assertTrue(c.hasLiveStream)
        c.stop()
        assertFalse("층도 측위도 없으면 끊는다", c.hasLiveStream)
        assertFalse(lifecycle.isObserving)

        c.setFloorMap(Floor("F1", "F1"), "B")
        c.start(p)
        c.stop()
        assertTrue("층을 보고 있으면 측위를 꺼도 콘솔 변경은 계속 받는다", c.hasLiveStream)
        assertTrue(lifecycle.isObserving)

        c.teardown()
        assertFalse(c.hasLiveStream)
        assertFalse(lifecycle.isObserving)
    }

    @Test fun foregroundReattachesStream() = runTest {
        val spy = StreamSpy()
        val lifecycle = FakeAppLifecycle()
        val c = makeCoordinator(server, lifecycle = lifecycle, liveFactory = spy.factory())
        c.prepare()
        c.setFloorMap(Floor("F1", "F1"), "B")
        assertEquals(1, spy.created.size)

        lifecycle.background()
        runCurrent()
        lifecycle.foreground()
        runCurrent()
        assertEquals("복귀하면 새로 붙인다(ResyncNeeded 는 스트림이 올린다)", 2, spy.created.size)
    }

    /**
     * 실기기 ProcessLifecycleOwner 는 이미 STARTED 인 프로세스에 관찰자를 붙이면 ON_START 를
     * addObserver 안에서 곧바로 다시 준다(catch-up). 그걸 흉내 낸다.
     */
    private class CatchUpLifecycle : AppLifecycle {
        var observing = false
        var onForeground: (() -> Unit)? = null

        override fun observe(onBackground: () -> Unit, onForeground: () -> Unit) {
            observing = true
            this.onForeground = onForeground
            onForeground() // 붙이는 순간 전경 통지가 동기로 들어온다
        }

        override fun stopObserving() {
            observing = false
            onForeground = null
        }
    }

    private fun StreamSpy.active(): Int = created.count { it.isStarted }

    /** 관찰자를 붙일 때의 catch-up 전경 통지가 스트림을 하나 더 열면 안 된다(ResyncNeeded 도 한 번). */
    @Test fun catchUpForegroundOnObserveOpensOnlyOneStream() = runTest {
        val spy = StreamSpy()
        val c = makeCoordinator(server, lifecycle = CatchUpLifecycle(), liveFactory = spy.factory())
        c.prepare()
        c.identify("pf")

        c.start(MockPositioningProvider())
        runCurrent()

        assertEquals("스트림은 하나만 만든다(=연결 시 ResyncNeeded 1회)", 1, spy.created.size)
        assertEquals(1, spy.active())
    }

    /** 층 없이 begin/end 를 되풀이해도 열린 스트림이 쌓이지 않는다. */
    @Test fun repeatedStartStopWithoutFloorDoesNotAccumulateStreams() = runTest {
        val spy = StreamSpy()
        val c = makeCoordinator(server, lifecycle = CatchUpLifecycle(), liveFactory = spy.factory())
        c.prepare()
        c.identify("pf")
        val p = MockPositioningProvider()

        repeat(3) {
            c.start(p)
            runCurrent()
            c.stop()
            runCurrent()
            assertEquals("end 뒤에 열린 스트림이 남았다", 0, spy.active())
        }
        c.start(p)
        runCurrent()
        assertEquals(1, spy.active())
    }

    /** 배경 없이 전경 통지가 거듭 와도 앞 스트림을 닫고 갈아 끼운다(열린 연결은 늘 하나). */
    @Test fun foregroundReplacesStreamWithoutLeaking() = runTest {
        val spy = StreamSpy()
        val lifecycle = FakeAppLifecycle()
        val c = makeCoordinator(server, lifecycle = lifecycle, liveFactory = spy.factory())
        c.prepare()
        c.setFloorMap(Floor("F1", "F1"), "B")

        lifecycle.foreground()
        runCurrent()
        lifecycle.foreground()
        runCurrent()

        assertEquals(3, spy.created.size)
        assertEquals("열린 스트림은 하나여야 한다", 1, spy.active())
    }

    // 스트림은 코디네이터가 넘긴 스코프 위에서 onChange 를 부른다 — 그대로 전달한다(재전환 없음).
    @Test fun streamChangeIsDeliveredAsIs() = runTest {
        val spy = StreamSpy()
        val c = makeCoordinator(server, liveFactory = spy.factory())
        c.prepare()
        c.setFloorMap(Floor("F1", "F1"), "B")
        val got = mutableListOf<ConfigChange>()
        c.onConfigChange = { got.add(it) }

        spy.onChange!!(ConfigChange.ZonesChanged("F1"))
        assertEquals(listOf<ConfigChange>(ConfigChange.ZonesChanged("F1")), got)
    }
}
