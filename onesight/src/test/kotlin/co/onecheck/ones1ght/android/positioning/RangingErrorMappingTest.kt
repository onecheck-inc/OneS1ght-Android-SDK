package co.onecheck.ones1ght.android.positioning

import co.onecheck.ones1ght.android.runtime.SdkErrorCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 레인징 사유(android.ranging.RangingSession.Callback 의 reason 상수) → SDK E-코드.
 *
 * reason 상수는 android.ranging 값을 쓰지 않고 같은 정수를 내부에 복제한다(JVM 테스트에서
 * android 클래스를 건드릴 수 없다). 값은 `javap -constants` 로 확인했다:
 * REASON_UNKNOWN=0, REASON_LOCAL_REQUEST=1, REASON_REMOTE_REQUEST=2, REASON_UNSUPPORTED=3,
 * REASON_SYSTEM_POLICY=4, REASON_NO_PEERS_FOUND=5.
 *
 * 포팅 원본(구조만 참고 — 매핑표 자체는 UWB 허브 오류(1~12)가 아니라 레인징 사유로 새로
 * 짠 것): UwbEngineErrorMappingTests.swift.
 */
class RangingErrorMappingTest {

    /** security 플래그가 서면 사유와 무관하게 항상 권한 거부다. */
    @Test fun securityFlagAlwaysMapsToPermissionDenied() {
        assertEquals(SdkErrorCode.PERMISSION_DENIED, RangingErrorMapping.code(RangingErrorMapping.REASON_UNKNOWN, security = true))
        assertEquals(SdkErrorCode.PERMISSION_DENIED, RangingErrorMapping.code(RangingErrorMapping.REASON_LOCAL_REQUEST, security = true))
        assertEquals(SdkErrorCode.PERMISSION_DENIED, RangingErrorMapping.code(RangingErrorMapping.REASON_NO_PEERS_FOUND, security = true))
    }

    @Test fun unsupportedMapsToDeviceNotSupported() {
        assertEquals(
            SdkErrorCode.DEVICE_NOT_SUPPORTED,
            RangingErrorMapping.code(RangingErrorMapping.REASON_UNSUPPORTED, security = false),
        )
    }

    @Test fun systemPolicyMapsToPermissionDenied() {
        assertEquals(
            SdkErrorCode.PERMISSION_DENIED,
            RangingErrorMapping.code(RangingErrorMapping.REASON_SYSTEM_POLICY, security = false),
        )
    }

    @Test fun noPeersFoundMapsToNoPositionFix() {
        assertEquals(
            SdkErrorCode.NO_POSITION_FIX,
            RangingErrorMapping.code(RangingErrorMapping.REASON_NO_PEERS_FOUND, security = false),
        )
    }

    @Test fun unknownAndRemoteRequestMapToUwbSessionFailed() {
        assertEquals(
            SdkErrorCode.UWB_SESSION_FAILED,
            RangingErrorMapping.code(RangingErrorMapping.REASON_UNKNOWN, security = false),
        )
        assertEquals(
            SdkErrorCode.UWB_SESSION_FAILED,
            RangingErrorMapping.code(RangingErrorMapping.REASON_REMOTE_REQUEST, security = false),
        )
    }

    /** 로컬(우리) 요청으로 닫힌 것은 정상 종료다 — 코드를 올리지 않는다. */
    @Test fun localRequestIsNormalStopAndMapsToNull() {
        assertNull(RangingErrorMapping.code(RangingErrorMapping.REASON_LOCAL_REQUEST, security = false))
    }

    /** 모르는 사유를 아무 코드에나 붙이지 않는다. */
    @Test fun unknownFutureReasonsAreNotGuessed() {
        assertNull(RangingErrorMapping.code(6, security = false))
        assertNull(RangingErrorMapping.code(99, security = false))
        assertNull(RangingErrorMapping.code(-1, security = false))
    }

    /** 매핑이 가리키는 코드는 전부 실재해야 한다. */
    @Test fun allMappedCodesExist() {
        val known = SdkErrorCode.entries.map { it.code }.toSet()
        val reasons = listOf(
            RangingErrorMapping.REASON_UNKNOWN,
            RangingErrorMapping.REASON_LOCAL_REQUEST,
            RangingErrorMapping.REASON_REMOTE_REQUEST,
            RangingErrorMapping.REASON_UNSUPPORTED,
            RangingErrorMapping.REASON_SYSTEM_POLICY,
            RangingErrorMapping.REASON_NO_PEERS_FOUND,
        )
        for (reason in reasons) {
            val code = RangingErrorMapping.code(reason, security = false) ?: continue
            assertTrue("$reason → 없는 코드 ${code.code}", known.contains(code.code))
        }
    }

    /** javap 로 확인한 android.ranging.RangingSession.Callback 의 reason 상수값 그대로다. */
    @Test fun reasonConstantsMatchAndroidRangingSessionCallback() {
        assertEquals(0, RangingErrorMapping.REASON_UNKNOWN)
        assertEquals(1, RangingErrorMapping.REASON_LOCAL_REQUEST)
        assertEquals(2, RangingErrorMapping.REASON_REMOTE_REQUEST)
        assertEquals(3, RangingErrorMapping.REASON_UNSUPPORTED)
        assertEquals(4, RangingErrorMapping.REASON_SYSTEM_POLICY)
        assertEquals(5, RangingErrorMapping.REASON_NO_PEERS_FOUND)
    }
}
