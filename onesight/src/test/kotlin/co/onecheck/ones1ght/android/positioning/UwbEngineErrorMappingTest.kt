package co.onecheck.ones1ght.android.positioning

import co.onecheck.ones1ght.android.runtime.SdkErrorCode
import co.onecheck.ones1ght.android.runtime.SdkLocalized
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 엔진 오류 코드(1~13) → SDK E-코드.
 *
 * 이 매핑이 있어야 엔진 고유의 실패가 콘솔 로그 분석기까지 간다. 화면 로그로만 남기면
 * 현장에서만 보이고 관리자는 "안 됐다"는 말만 듣는다.
 *
 * ⚠️ 전부 올리지는 않는다. 2(이미 측위 중)·8(정지 중 start)은 호출 순서 문제라
 *    현장 진단 가치가 없고, 재시도마다 쌓여 진짜 오류를 덮는다.
 *
 * 포팅 원본: UwbEngineErrorMappingTests.swift. 안드로이드 엔진에만 있는 13(BLE 스캔 시작 제한)이 더해졌다.
 */
class UwbEngineErrorMappingTest {

    private fun code(hub: Int): SdkErrorCode? = UwbPositioningProvider.sdkCode(hub)

    /** 권한 계열 셋(BT 불가·위치 불가·매니페스트 선언 누락)은 모두 E2003 이다 — 할 일이 같다. */
    @Test fun permissionFamilyMapsToPermissionDenied() {
        assertEquals(SdkErrorCode.PERMISSION_DENIED, code(3))
        assertEquals(SdkErrorCode.PERMISSION_DENIED, code(7))
        assertEquals(SdkErrorCode.PERMISSION_DENIED, code(9))
    }

    /** 라이선스 계열은 키 문제다 — 재시도해도 소용없다는 뜻이 담긴 코드로 간다. */
    @Test fun licenseFamilyMapsToInvalidKey() {
        assertEquals("라이선스 미등록", SdkErrorCode.INVALID_KEY, code(1))
        assertEquals("서버가 거부", SdkErrorCode.INVALID_KEY, code(10))
    }

    /** 라이선스 서버에 못 닿은 것은 키 문제가 아니라 통신 문제다 — 재시도가 유효하다. */
    @Test fun unreachableLicenseServerIsNetwork() {
        assertEquals(SdkErrorCode.NETWORK, code(11))
    }

    @Test fun positioningFailuresMapToTheirOwnCodes() {
        assertEquals(SdkErrorCode.LOCATORS_MISSING, code(4))
        assertEquals(SdkErrorCode.UWB_SESSION_FAILED, code(5))
        assertEquals(SdkErrorCode.AREA_JUDGE_FAILED, code(6))
        assertEquals(SdkErrorCode.DEVICE_NOT_SUPPORTED, code(12))
    }

    /**
     * 13(BLE 스캔 시작이 너무 잦음)은 층 탐색을 못 한 것이라 E3007 이다. 권한·키 문제로 올리면
     * 관리자가 엉뚱한 곳을 본다 — 잠시 뒤 다시 시작하면 풀리는 일시적 제한이다.
     */
    @Test fun scanThrottleIsFloorNotDetected() {
        assertEquals(SdkErrorCode.FLOOR_NOT_DETECTED, code(13))
        assertNotEquals(SdkErrorCode.PERMISSION_DENIED, code(13))
    }

    /** 호출 순서 문제는 올리지 않는다 — 재시도마다 쌓여 진짜 오류를 덮는다. */
    @Test fun callOrderComplaintsAreNotPromoted() {
        assertNull("이미 측위 중", code(2))
        assertNull("정지가 끝나기 전 start", code(8))
    }

    /** 모르는 코드를 아무 데나 붙이지 않는다 — 엔진이 코드를 늘리면 매핑에 없다는 사실이 드러나야 한다. */
    @Test fun unknownCodesAreNotGuessed() {
        assertNull(code(0))
        assertNull(code(14))
        assertNull(code(99))
    }

    /** 로그 문구 표도 1~13 을 전부 갖는다 — 빠지면 키 이름이 그대로 찍힌다. */
    @Test fun everyEngineCodeHasADescription() {
        SdkLocalized.language = "en"
        try {
            for (hub in 1..13) {
                val text = UwbPositioningProvider.describe(hub)
                assertNotEquals("uwb.err$hub 문구 없음", "uwb.err$hub", text)
            }
            assertEquals(SdkLocalized.t("uwb.errUnknown"), UwbPositioningProvider.describe(42))
        } finally {
            SdkLocalized.language = null
        }
    }

    /** 시작 단계에서 되돌리는 코드 집합 — 2·8 은 상태를 안 바꾸는 호출 순서 불평이라 빠진다. */
    @Test fun startAbortCodesExcludeCallOrderComplaints() {
        assertEquals(setOf(1, 3, 7, 9, 10, 11, 12, 13), UwbPositioningProvider.START_ABORT_CODES)
    }
}
