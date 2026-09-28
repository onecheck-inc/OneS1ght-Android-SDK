package co.onecheck.ones1ght.android.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 등급 기준을 코드로 고정한다 — SdkErrorCode.kt 머리말의 "등급 기준"이 말로만 남으면,
 * 다음 사람이 새 코드를 넣을 때 습관대로 ERROR 를 붙인다.
 *
 * 0.1.18 까지 실제로 그랬다: 미지원 기기·꺼 둔 설정·구역 없는 층처럼 **고장이 아닌
 * 상태**가 전부 ERROR 로 찍혀, 정상 기기들이 콘솔 로그 분석기를 채우고 그 소음 속에
 * 진짜 고장이 묻혔다.
 *
 * 포팅 원본: SdkLogLevelPolicyTests.swift.
 */
class SdkLogLevelPolicyTest {

    /**
     * 기기가 못 하는 것은 고장이 아니다 — ERROR 가 아니어야 한다.
     *
     * 이 둘은 "미지원 기기에서도 앱은 살아 있다"는 설계 보장의 바로 그 상태다.
     * 여기가 ERROR 로 돌아가면 그 보장이 로그상으로는 사고처럼 보인다.
     *
     * ⚠️ 그렇다고 INFO 도 아니다 — 관리자에게는 고칠 것이 없어 보여도 앱 개발자에게는
     * 할 일이 있고(안내 화면), 콘솔 코드집이 "INFO 는 조치를 갖지 않는다"를 불변식으로
     * 둔다. WARN 이 이 둘의 자리다.
     */
    @Test fun deviceFactsAreWarnNotError() {
        assertEquals(SdkLogLevel.WARN, SdkErrorCode.OS_VERSION_TOO_LOW.level)
        assertEquals(SdkLogLevel.WARN, SdkErrorCode.DEVICE_NOT_SUPPORTED.level)
    }

    /** 정상 경로이거나 계속 동작하는 상태는 ERROR 가 아니다. */
    @Test fun benignStatesAreNotError() {
        val benign = listOf(
            SdkErrorCode.OS_VERSION_TOO_LOW, SdkErrorCode.DEVICE_NOT_SUPPORTED,
            SdkErrorCode.POSITIONING_DISABLED, // 테넌트가 일부러 꺼 둔 설정
            SdkErrorCode.PERMISSION_DENIED, // 설정에서 풀 수 있다
            SdkErrorCode.FLOOR_NOT_SET, // BLE 흐름에서는 정상 경로
            SdkErrorCode.LOCATORS_MISSING, // 아직 설치 전인 층일 수 있다
            SdkErrorCode.SESSION_ID_MISSING,
            SdkErrorCode.ZONES_EMPTY, // 구역이 없을 뿐 — 지도는 그대로 뜬다
            SdkErrorCode.FLOOR_NOT_DETECTED,
            SdkErrorCode.ZONE_MAPPING_FAILED,
            SdkErrorCode.AREA_JUDGE_FAILED,
            SdkErrorCode.LOCATOR_NOT_RECEIVED,
            SdkErrorCode.PENDING_DROPPED,
        )
        for (code in benign) {
            assertNotEquals("${code.code} 는 고장이 아니다", SdkLogLevel.ERROR, code.level)
        }
    }

    /** 진짜 실패는 ERROR 로 남아야 한다 — 위 완화가 과하게 번지면 이 테스트가 잡는다. */
    @Test fun realFailuresStayError() {
        val real = listOf(
            SdkErrorCode.NOT_INITIALIZED, SdkErrorCode.INVALID_KEY, SdkErrorCode.NOT_IDENTIFIED, SdkErrorCode.KEY_UNAVAILABLE,
            SdkErrorCode.LOCATORS_FETCH_FAILED, SdkErrorCode.FLOOR_ID_MISMATCH, SdkErrorCode.UWB_SESSION_FAILED,
            SdkErrorCode.NO_POSITION_FIX, // 3대 이상 들리는데 못 푼다 = 배치 불일치
            SdkErrorCode.NETWORK, SdkErrorCode.SERVER, SdkErrorCode.UNPROCESSABLE, SdkErrorCode.FORBIDDEN, SdkErrorCode.DECODING,
        )
        for (code in real) {
            assertEquals("${code.code} 는 진짜 실패다", SdkLogLevel.ERROR, code.level)
        }
    }

    /**
     * 모든 코드가 위 두 목록(+ 그 사이 WARN 계열) 중 하나에는 들어 있어야 한다 — 새 코드를
     * 넣고 이 목록을 안 고치면 여기서 걸린다(등급을 생각 안 하고 넘어가는 것을 막는 자리다).
     */
    @Test fun everyCodeIsClassified() {
        val known = setOf(
            SdkErrorCode.OS_VERSION_TOO_LOW, SdkErrorCode.DEVICE_NOT_SUPPORTED,
            SdkErrorCode.POSITIONING_DISABLED, SdkErrorCode.PERMISSION_DENIED, SdkErrorCode.FLOOR_NOT_SET,
            SdkErrorCode.LOCATORS_MISSING, SdkErrorCode.SESSION_ID_MISSING, SdkErrorCode.ZONES_EMPTY,
            SdkErrorCode.NO_POSITION_FIX, SdkErrorCode.FLOOR_NOT_DETECTED, SdkErrorCode.ZONE_MAPPING_FAILED,
            SdkErrorCode.AREA_JUDGE_FAILED, SdkErrorCode.LOCATOR_NOT_RECEIVED, SdkErrorCode.PENDING_DROPPED,
            SdkErrorCode.NOT_INITIALIZED, SdkErrorCode.INVALID_KEY, SdkErrorCode.NOT_IDENTIFIED, SdkErrorCode.KEY_UNAVAILABLE,
            SdkErrorCode.LOCATORS_FETCH_FAILED, SdkErrorCode.FLOOR_ID_MISMATCH, SdkErrorCode.UWB_SESSION_FAILED,
            SdkErrorCode.NETWORK, SdkErrorCode.SERVER, SdkErrorCode.UNPROCESSABLE, SdkErrorCode.FORBIDDEN, SdkErrorCode.DECODING,
        )
        val missing = SdkErrorCode.entries.filterNot { it in known }
        assertTrue("등급이 분류되지 않은 코드: ${missing.map { it.code }}", missing.isEmpty())
    }

    @Test fun has26ErrorCodesAnd7Info() {
        assertEquals(26, SdkErrorCode.entries.size)
        assertEquals(7, SdkInfoCode.entries.size)
    }

    /** E1005·E1006·E3005 는 결번이다 — 옛 로그의 의미가 바뀌면 안 되므로 다시 쓰지 않는다. */
    @Test fun retiredNumbersNeverReused() {
        val codes = SdkErrorCode.entries.map { it.code }
        listOf("E1005", "E1006", "E3005").forEach { assertFalse(it in codes) }
    }

    /** iOS SdkErrorCode.swift 의 (code, level) 26쌍을 그대로 옮긴 표 — 사양 동일성 가드. */
    @Test fun codesAndLevelsMatchIos() {
        val ios = mapOf(
            "E1001" to "ERROR",
            "E1002" to "ERROR",
            "E1003" to "WARN",
            "E1004" to "ERROR",
            "E1007" to "ERROR",
            "E2001" to "WARN",
            "E2002" to "WARN",
            "E2003" to "WARN",
            "E3001" to "WARN",
            "E3002" to "WARN",
            "E3003" to "WARN",
            "E3004" to "WARN",
            "E3006" to "ERROR",
            "E3007" to "WARN",
            "E3008" to "ERROR",
            "E3009" to "WARN",
            "E4001" to "ERROR",
            "E4002" to "ERROR",
            "E4003" to "WARN",
            "E4004" to "WARN",
            "E5001" to "ERROR",
            "E5002" to "ERROR",
            "E5003" to "ERROR",
            "E5004" to "ERROR",
            "E5005" to "ERROR",
            "E5006" to "WARN",
        )
        assertEquals(ios, SdkErrorCode.entries.associate { it.code to it.level.wire })
    }
}
