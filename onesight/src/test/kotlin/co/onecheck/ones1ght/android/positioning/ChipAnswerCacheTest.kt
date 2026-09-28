@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package co.onecheck.ones1ght.android.positioning

//
//  ChipAnswerCacheTest.kt
//  칩 조회 답 기억 — 시간 초과를 미지원으로 굳혀, 동기 판정이 다시는 메인을 막지 않게 한다.
//

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChipAnswerCacheTest {

    @Test fun answerIsRemembered() = runTest {
        val cache = ChipAnswerCache(5_000)
        var asked = 0
        assertNull(cache.known)
        assertTrue(cache.get { asked += 1; true })
        assertTrue(cache.get { asked += 1; false })
        assertEquals(1, asked)
        assertEquals(true, cache.known)
    }

    /** 답이 안 오면 5초 뒤 미지원 — 그리고 그 사실을 기억해 다음에는 기다리지 않는다. */
    @Test fun timeoutIsRememberedAsNotSupported() = runTest {
        val cache = ChipAnswerCache(5_000)
        var asked = 0
        assertFalse(cache.get { asked += 1; awaitCancellation() })
        assertEquals(5_000L, currentTime)
        assertEquals(false, cache.known)

        assertFalse(cache.get { asked += 1; true })
        assertEquals("시간 초과 뒤에 다시 물었다(=다시 5초 막는다)", 1, asked)
        assertEquals(5_000L, currentTime)
    }

    /** 조회 자체가 실패하면(null) 모름 — 기억하지 않고 다음에 다시 묻는다. */
    @Test fun queryFailureIsNotRemembered() = runTest {
        val cache = ChipAnswerCache(5_000)
        assertFalse(cache.get { null })
        assertNull(cache.known)
        assertTrue(cache.get { true })
        assertEquals(true, cache.known)
    }
}
