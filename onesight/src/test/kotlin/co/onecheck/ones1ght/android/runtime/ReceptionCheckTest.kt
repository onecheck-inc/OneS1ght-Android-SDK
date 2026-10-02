@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package co.onecheck.ones1ght.android.runtime

//
//  ReceptionCheckTest.kt
//  로케이터 수신 진단 — 무엇을 알리고, 무엇을 알리지 않는가.
//
//  앵커 세트는 마스터 1대와 서브 여러 대로 이루어지고, 마스터가 살아 있는 한 서브가 빠져도
//  측위는 계속된다. 그런 상태를 ERROR 로 올리면 멀쩡한 현장에서 계속 울린다. 그래서 WARN 이다.
//
//  포팅 원본: ReceptionCheckTests.swift.
//

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
import org.junit.Rule
import org.junit.Test

/** 진단을 낼 수 있는 provider. 값은 테스트가 정한다. */
private class DiagnosticProvider(var diagnostic: PositioningDiagnostic?) : PositioningProvider {
    override var delegate: PositioningProviderDelegate? = null
    override val positioningDiagnostic: PositioningDiagnostic? get() = diagnostic
    override fun start() {}
    override fun stop() {}
}

class ReceptionCheckTest {

    @get:Rule val server = MockWebServer()

    // 이 테스트가 보는 것은 진단 판정뿐이라 공용 기본 응답(verify 통과·그 밖 통과)으로 충분하다(감사 K16).
    private val fx = CoordinatorFixture(server)

    /** 진단을 붙인 채 측위를 켜고, 확인이 돌 때까지(가상 7초) 기다린 뒤 남은 로그를 돌려준다. */
    private suspend fun TestScope.runCheck(diagnostic: PositioningDiagnostic?): List<String> {
        with(fx) { started(DiagnosticProvider(diagnostic)) }
        advanceTimeBy(7_001)
        runCurrent()
        return fx.lines.map { it.second }
    }

    private fun hasCode(lines: List<String>, code: String) = lines.any { it.contains("[$code]") }

    /** 7초 전에는 보지 않는다 — 엔진이 자리 잡기 전의 미수신은 정상이다. */
    @Test fun checkRunsOnlyAfterSevenSeconds() = runTest {
        val c = makeCoordinator(server)
        val lines = mutableListOf<String>()
        c.onLog = { _, line -> lines.add(line) }
        c.prepare()
        c.identify("pf")
        c.start(DiagnosticProvider(PositioningDiagnostic(4, 3, 3, listOf(0x9DD7), hasFix = true)))

        advanceTimeBy(6_900)
        runCurrent()
        assertFalse(hasCode(lines, "E4003"))
        advanceTimeBy(200)
        runCurrent()
        assertTrue(hasCode(lines, "E4003"))
    }

    /** 멈추면 확인도 취소된다. */
    @Test fun stopCancelsCheck() = runTest {
        val c = makeCoordinator(server)
        val lines = mutableListOf<String>()
        c.onLog = { _, line -> lines.add(line) }
        c.prepare()
        c.identify("pf")
        c.start(DiagnosticProvider(PositioningDiagnostic(4, 3, 3, listOf(0x9DD7), hasFix = true)))
        c.stop()

        advanceTimeBy(10_000)
        runCurrent()
        assertFalse(hasCode(lines, "E4003"))
    }

    /** ⚠️ 서브가 빠진 상태는 **알리되 막지 않는다.** */
    @Test fun missingLocatorIsReportedAsWarning() = runTest {
        val lines = runCheck(PositioningDiagnostic(4, 3, 3, listOf(0x9DD7), hasFix = true))

        assertTrue("미수신을 알려야 유지보수가 시작된다: $lines", hasCode(lines, "E4003"))
        assertEquals("측위가 계속되는 상태라 ERROR 가 아니다", SdkLogLevel.WARN, SdkErrorCode.LOCATOR_NOT_RECEIVED.level)
    }

    /** 어느 주소가 빠졌는지 로그에 남아야 한다 — 현장에서 기기 라벨과 대조할 값이다. */
    @Test fun missingAddressAppearsInTheLog() = runTest {
        val lines = runCheck(PositioningDiagnostic(4, 3, 3, listOf(0x9DD7), hasFix = true))

        assertTrue(lines.toString(), lines.any { it.contains("0x9DD7") })
        assertTrue(
            lines.toString(),
            lines.any { it.startsWith("[E4003]") && it.endsWith("registered=4 received=3 missing=0x9DD7") },
        )
    }

    @Test fun receptionCheckReportsMissingAnchors() = runTest {
        val lines = runCheck(PositioningDiagnostic(3, 2, 2, listOf(0x0B4B), hasFix = true))

        assertTrue(lines.toString(), lines.any { it.startsWith("[E4003]") && it.contains("0x0B4B") })
    }

    /** 다 잡히고 좌표도 나오면 아무 말도 하지 않는다. 조용한 것이 정상이다. */
    @Test fun healthySessionSaysNothing() = runTest {
        val lines = runCheck(PositioningDiagnostic(4, 4, 4, emptyList(), hasFix = true))

        assertFalse(lines.toString(), hasCode(lines, "E4003"))
        assertFalse(lines.toString(), hasCode(lines, "E4002"))
    }

    /** 신호는 충분히 잡히는데 좌표가 안 나오는 것은 배치 어긋남 — 측위가 실제로 막혀 있다. ERROR. */
    @Test fun signalWithoutFixIsAnError() = runTest {
        val lines = runCheck(PositioningDiagnostic(4, 4, 4, emptyList(), hasFix = false))

        assertTrue(lines.toString(), lines.any { it.startsWith("[E4002]") && it.endsWith("matched=4 fix=none") })
        assertEquals(SdkLogLevel.ERROR, SdkErrorCode.NO_POSITION_FIX.level)
    }

    /** 잡힌 것이 3대 미만이면 좌표가 안 나오는 게 당연하다 — 배치 불일치로 몰지 않는다. */
    @Test fun tooFewLocatorsIsNotReportedAsPlacementProblem() = runTest {
        val lines = runCheck(PositioningDiagnostic(4, 2, 2, listOf(0x0001, 0x0002), hasFix = false))

        assertTrue("미수신은 알린다: $lines", hasCode(lines, "E4003"))
        assertTrue(lines.toString(), lines.any { it.endsWith("missing=0x0001,0x0002") })
        assertFalse("원인이 다른데 배치 문제로 안내하면 헛수고를 시킨다: $lines", hasCode(lines, "E4002"))
    }

    /** 진단을 못 내는 provider(Mock 등)에서는 아무 말도 하지 않는다. */
    @Test fun providerWithoutDiagnosticStaysQuiet() = runTest {
        val lines = runCheck(null)

        assertFalse(lines.toString(), hasCode(lines, "E4003"))
        assertFalse(lines.toString(), hasCode(lines, "E4002"))
    }

    // MARK: - 앵커별 특정이 안 되는 엔진

    /** 등록만 알고 나머지는 모르는 진단에서 좌표가 안 나오면 반드시 알려야 한다. */
    @Test fun engineWithoutPerAnchorDetailStillReportsMissingFix() = runTest {
        val lines = runCheck(PositioningDiagnostic(4, 0, 0, emptyList(), hasFix = false, canAttributePerAnchor = false))

        assertTrue("앵커별 특정을 못 해도 '좌표가 없다'는 사실은 남겨야 한다: $lines", hasCode(lines, "E4002"))
        assertTrue(
            lines.toString(),
            lines.any { it.endsWith("registered=4 fix=none (per-anchor detail unavailable)") },
        )
    }

    /** 다만 **미수신을 단정하지는 않는다.** */
    @Test fun engineWithoutPerAnchorDetailDoesNotBlameLocators() = runTest {
        val lines = runCheck(PositioningDiagnostic(4, 0, 0, emptyList(), hasFix = false, canAttributePerAnchor = false))

        assertFalse("모르는 것을 고장으로 칠하면 안 된다: $lines", hasCode(lines, "E4003"))
    }

    /** 좌표가 나오고 있으면 조용하다. */
    @Test fun engineWithoutPerAnchorDetailStaysQuietWhenFixed() = runTest {
        val lines = runCheck(PositioningDiagnostic(4, 4, 4, emptyList(), hasFix = true, canAttributePerAnchor = false))

        assertFalse(lines.toString(), hasCode(lines, "E4002"))
        assertFalse(lines.toString(), hasCode(lines, "E4003"))
    }

    /** 등록된 로케이터가 하나도 없으면 좌표가 없는 게 당연하다. */
    @Test fun engineWithoutPerAnchorDetailAndNoLocatorsStaysQuiet() = runTest {
        val lines = runCheck(PositioningDiagnostic(0, 0, 0, emptyList(), hasFix = false, canAttributePerAnchor = false))

        assertFalse(lines.toString(), hasCode(lines, "E4002"))
    }

    /** 기본값은 예전 그대로여야 한다. */
    @Test fun perAnchorAttributionDefaultsToTrue() {
        val d = PositioningDiagnostic(1, 1, 1, emptyList(), hasFix = true)
        assertTrue(d.canAttributePerAnchor)
    }
}
