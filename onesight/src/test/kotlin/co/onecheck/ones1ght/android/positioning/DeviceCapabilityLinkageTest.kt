package co.onecheck.ones1ght.android.positioning

import android.content.ContextWrapper
import co.onecheck.ones1ght.android.runtime.LogLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 감사 SP-B13 — 엔진 클래스가 빠졌거나(R8/ProGuard 가 지움) 링크가 깨지면 그건 「미지원 기기(E2002)」가 아니라
 * 앱 빌드 문제다. 예전엔 Throwable 을 통째로 삼켜 미지원으로 위장했다. 판정은 여전히 false(던지지 않는다)지만
 * 원인을 ERROR 로 드러낸다. 일반 예외는 종전대로 조용히 false.
 */
class DeviceCapabilityLinkageTest {

    @Test fun linkageErrorIsSurfacedNotDisguised() {
        val logs = mutableListOf<Pair<LogLevel, String>>()
        val cap = AndroidDeviceCapability(
            contextProvider = { ContextWrapper(null) },
            hardware = { throw NoClassDefFoundError("kr/geoplan/android/lib/ihub/IntelligenceHub") },
            sdkIntProvider = { 37 },
            onLinkageError = { level, msg -> logs += level to msg },
        )
        assertFalse(cap.hasUwbHardware())
        assertEquals(1, logs.size)
        assertEquals(LogLevel.ERROR, logs[0].first)
        assertTrue(logs[0].second, logs[0].second.contains("NoClassDefFoundError"))
    }

    @Test fun ordinaryExceptionIsQuietFalse() {
        val logs = mutableListOf<Pair<LogLevel, String>>()
        val cap = AndroidDeviceCapability(
            contextProvider = { ContextWrapper(null) },
            hardware = { throw IllegalStateException("no service") },
            sdkIntProvider = { 37 },
            onLinkageError = { level, msg -> logs += level to msg },
        )
        assertFalse(cap.hasUwbHardware())
        assertTrue(logs.isEmpty())
    }
}
