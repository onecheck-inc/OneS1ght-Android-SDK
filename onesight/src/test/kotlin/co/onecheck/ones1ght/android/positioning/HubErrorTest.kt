package co.onecheck.ones1ght.android.positioning

import co.onecheck.ones1ght.android.runtime.SdkErrorCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 엔진 오류 번호의 뜻이 한 곳([HubError])에서만 정해지는가 — 감사 SP-C4 · iOS K15(HubErrorTests.swift 와 같은 축).
 * 집합은 예전에 자리마다 박혀 있던 값 그대로다(동작 무변경).
 */
class HubErrorTest {

    @Test fun numbersAreOneThroughThirteen() {
        assertEquals((1..13).toList(), HubError.entries.map { it.code })
        assertNull(HubError.of(0))
        assertNull(HubError.of(14))
    }

    /** 시작 단계에서 나면 엔진이 아예 못 뜬 것 — 예전 START_ABORT_CODES. iOS [1,3,7,9,10,11,12] + 안드로이드 13. */
    @Test fun abortsStartSet() {
        assertEquals(setOf(1, 3, 7, 9, 10, 11, 12, 13), HubError.entries.filter { it.abortsStart }.map { it.code }.toSet())
        assertEquals(UwbPositioningProvider.START_ABORT_CODES, setOf(1, 3, 7, 9, 10, 11, 12, 13))
    }

    /** 사람이 풀어야 하는 것 — 예전 NOT_RETRYABLE_CODES. iOS [1,3,7,10] + 안드로이드 9·12. */
    @Test fun needsPersonSet() {
        assertEquals(setOf(1, 3, 7, 9, 10, 12), HubError.entries.filter { it.needsPerson }.map { it.code }.toSet())
        assertEquals(true, UwbPositioningProvider.isRetryable(null))
        assertEquals(true, UwbPositioningProvider.isRetryable(99))
        assertEquals(false, UwbPositioningProvider.isRetryable(9))
    }

    /** 호출 순서 문제(2·8)만 코드로 올리지 않는다. */
    @Test fun onlyCallOrderComplaintsAreSilent() {
        assertEquals(setOf(2, 8), HubError.entries.filter { it.sdkCode() == null }.map { it.code }.toSet())
        assertEquals(
            SdkErrorCode.BLUETOOTH_OFF,
            HubError.BLUETOOTH_UNAVAILABLE.sdkCode("bluetooth unavailable: powered off"),
        )
        assertEquals(SdkErrorCode.FLOOR_NOT_DETECTED, HubError.SCAN_THROTTLED.sdkCode())
    }
}
