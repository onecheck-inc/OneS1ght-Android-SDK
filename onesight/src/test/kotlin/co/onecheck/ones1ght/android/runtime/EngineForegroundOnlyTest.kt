@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package co.onecheck.ones1ght.android.runtime

//
//  EngineForegroundOnlyTest.kt
//  측위 엔진은 앱이 화면에 있을 때만 돈다(2026-10-16).
//
//  화면 없이 깨어난 프로세스(서비스·리시버·WorkManager·액티비티 onCreate 안)에서 begin() 하면 엔진이 배경에서
//  켜졌다. 그 경로엔 배경 통지가 오지 않아 층 미탐지 감시·수신 점검이 화면 없이 걸린 채 남았고, 복귀의
//  provider.start() 는 이미 「도는 중」 이라 아무 일도 하지 않았다.
//  여기서 지키는 것: 화면 밖 시작은 엔진을 미루고, 화면에 오면 **한 번만** 켜고, 배경에선 끄고 타이머도 거둔다.
//
//  포팅 원본: EngineForegroundOnlyTests.swift(iOS #67). iOS 의 willTerminate 정지·남은 비콘 영역 정리는
//  안드로이드에 해당이 없어 옮기지 않았다(SessionCoordinator 생명주기 주석).
//

import co.onecheck.ones1ght.android.positioning.MockPositioningProvider
import co.onecheck.ones1ght.android.positioning.PositioningDiagnostic
import co.onecheck.ones1ght.android.positioning.PositioningProvider
import co.onecheck.ones1ght.android.positioning.PositioningProviderDelegate
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** 진단을 내는 provider — 수신 점검이 돌았는지(E4003)로 「점검이 걸렸다」 를 본다. */
private class CountingDiagnosticProvider : PositioningProvider {
    override var delegate: PositioningProviderDelegate? = null
    var startCount = 0
    override val positioningDiagnostic: PositioningDiagnostic =
        PositioningDiagnostic(4, 3, 3, listOf(0x9DD7), hasFix = true)

    override fun start() {
        startCount += 1
    }

    override fun stop() {}
}

class EngineForegroundOnlyTest {

    @get:Rule val server = MockWebServer()

    private val fx = CoordinatorFixture(server)
    private lateinit var provider: MockPositioningProvider
    private lateinit var life: FakeAppLifecycle
    private var active = true

    @Before fun setUp() {
        SdkLocalized.language = "ko"
        provider = MockPositioningProvider()
        life = FakeAppLifecycle()
        active = true
    }

    /** prepare → identify 까지 하고, 화면 상태를 이 테스트가 보도록 바꿔 끼운다. */
    private suspend fun TestScope.prepared(): SessionCoordinator {
        val c = makeCoordinator(server, lifecycle = life)
        c.onLog = { level, line -> fx.lines += level to line }
        c.isAppActive = { active }
        c.prepare()
        c.identify("pf_8a3c")
        return c
    }

    private fun TestScope.foreground() {
        active = true
        life.foreground()
        runCurrent()
    }

    private fun TestScope.background() {
        active = false
        life.background()
        runCurrent()
    }

    private val deferredLine get() = SdkLocalized.t("coord.engineDeferred")

    /** 화면 밖에서 시작하면 세션은 열리지만 엔진은 켜지 않는다 — 정보 로그 한 줄. */
    @Test fun startWhileInactiveDefersEngine() = runTest {
        val c = prepared()
        active = false

        c.start(provider)

        assertTrue("세션은 논리적으로 열려 있다", c.isRunning)
        assertTrue(c.visitorId.isNotEmpty())
        assertEquals("화면 밖에서는 엔진을 켜지 않는다", 0, provider.startCount)
        assertTrue(c.engineAwaitingForeground)
        assertTrue("생명주기는 그대로 관찰한다 — 화면에 오면 켜야 한다", life.isObserving)
        assertEquals("미룬다는 로그는 한 번", 1, fx.lines.count { it.second == deferredLine })
        assertTrue("positioningOn(I4001) 은 그대로 남긴다: ${fx.lines}", fx.hasCode("I4001"))
        c.stop()
    }

    /** 화면에 오면 한 번만 켠다 — 통지가 겹쳐 와도. */
    @Test fun becomingActiveStartsEngineExactlyOnce() = runTest {
        val c = prepared()
        active = false
        c.start(provider)

        foreground()
        foreground()
        foreground()

        assertEquals(1, provider.startCount)
        assertTrue(provider.isRunning)
        assertFalse(c.engineAwaitingForeground)
        c.stop()
    }

    /** 미뤄 둔 엔진은 화면에 오면서 수신 점검도 그때 건다(시작 시점이 아니라). */
    @Test fun receptionCheckArmsOnlyAfterForegroundStart() = runTest {
        val diag = CountingDiagnosticProvider()
        val c = prepared()
        active = false
        c.start(diag)
        advanceTimeBy(10_000)
        runCurrent()
        assertFalse("엔진을 안 켰으니 수신 점검도 걸지 않는다: ${fx.lines}", fx.hasCode("E4003"))

        foreground()
        assertEquals(1, diag.startCount)
        advanceTimeBy(7_001)
        runCurrent()
        assertTrue("화면에 온 뒤 수신 점검이 돌지 않았다: ${fx.lines}", fx.hasCode("E4003"))
        c.stop()
    }

    /** 배경에서 끄고, 복귀하면 다시 켠다(한 번). */
    @Test fun backgroundStopThenForegroundRestarts() = runTest {
        val c = prepared()
        c.start(provider)
        assertEquals(1, provider.startCount)

        background()
        assertFalse(provider.isRunning)
        assertTrue(c.engineAwaitingForeground)

        foreground()
        foreground()
        assertEquals(2, provider.startCount)
        assertTrue(provider.isRunning)
        assertFalse(c.engineAwaitingForeground)
        c.stop()
    }

    /** 배경 정지는 수신 점검을 거둔다 — 복귀하면 다시 건다. */
    @Test fun backgroundCancelsReceptionCheck() = runTest {
        val diag = CountingDiagnosticProvider()
        val c = prepared()
        c.start(diag)
        advanceTimeBy(2_000)

        background()
        advanceTimeBy(10_000)
        runCurrent()
        assertFalse("배경으로 간 뒤 수신 점검이 깨어났다: ${fx.lines}", fx.hasCode("E4003"))

        foreground()
        advanceTimeBy(7_001)
        runCurrent()
        assertTrue("복귀 뒤 수신 점검을 다시 걸지 않았다: ${fx.lines}", fx.hasCode("E4003"))
        c.stop()
    }

    /** 화면에 있을 때의 시작은 예전 그대로 — 바로 켜고, 겹친 포그라운드 통지는 아무것도 하지 않는다. */
    @Test fun startWhileActiveBehavesAsBefore() = runTest {
        val c = prepared()
        c.start(provider)

        assertEquals(1, provider.startCount)
        assertTrue(provider.isRunning)
        assertFalse(c.engineAwaitingForeground)
        assertFalse(fx.lines.any { it.second == deferredLine })

        foreground() // 배경 없이 온 통지
        assertEquals("도는 엔진을 다시 건드리지 않는다", 1, provider.startCount)
        c.stop()
    }

    /** 미룬 채 배경·복귀를 오가도 켜는 것은 화면에 온 그 한 번이다. */
    @Test fun deferredThenBackgroundThenForegroundStartsOnce() = runTest {
        val c = prepared()
        active = false
        c.start(provider)

        background()
        assertEquals(0, provider.startCount)
        assertTrue(c.engineAwaitingForeground)

        foreground()
        assertEquals(1, provider.startCount)
        assertTrue(provider.isRunning)
        c.stop()
    }

    /** 끝난 세션은 화면에 와도 켜지 않는다 — 늦게 도착한 통지도. */
    @Test fun becomingActiveAfterStopDoesNothing() = runTest {
        val c = prepared()
        active = false
        c.start(provider)
        // 층 없이 멈추면 관찰자를 떼므로, 떼기 전에 붙은 통지를 쥐어 둔다(늦게 도착한 통지 흉내).
        val lateForeground = life.onForeground!!
        c.stop()
        assertFalse(c.engineAwaitingForeground)

        active = true
        lateForeground()
        runCurrent()
        assertEquals(0, provider.startCount)
        assertFalse(provider.isRunning)
    }
}
