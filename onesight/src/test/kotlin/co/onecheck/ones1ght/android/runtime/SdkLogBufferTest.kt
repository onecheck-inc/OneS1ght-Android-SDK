package co.onecheck.ones1ght.android.runtime

import co.onecheck.ones1ght.android.model.SdkLogEntry
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SDK 로그 버퍼 — ERROR 즉시 flush · 임계 50건 flush 예약 · 2000건 상한(오래된 것부터 버림) ·
 * 전송 실패는 버린다(좌표 버퍼와 달리 재시도하지 않는다). 브리핑 명시 케이스(iOS 대응 파일 없음).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SdkLogBufferTest {

    private fun entry(code: String, level: String = "LOG"): SdkLogEntry =
        SdkLogEntry(code = code, level = level, message = "m", at = "t")

    // ERROR 1건 → 즉시 1회 전송
    @Test fun appendErrorTriggersImmediateFlush() = runTest {
        val sent = mutableListOf<List<SdkLogEntry>>()
        val buffer = SdkLogBuffer(send = { batch -> sent.add(batch); true }, scope = this)

        buffer.append(entry("E1", level = "ERROR"))
        advanceUntilIdle()

        assertEquals(1, sent.size)
        assertEquals(listOf("E1"), sent[0].map { it.code })
        assertEquals(0, buffer.count)
    }

    // 49건 → 전송 없음 (임계 50 미만)
    @Test fun append49EntriesDoesNotFlush() = runTest {
        val sent = mutableListOf<List<SdkLogEntry>>()
        val buffer = SdkLogBuffer(send = { batch -> sent.add(batch); true }, scope = this)

        repeat(49) { buffer.append(entry("L$it")) }
        advanceUntilIdle()

        assertTrue(sent.isEmpty())
        assertEquals(49, buffer.count)
    }

    // 50건째 → flush 예약(임계 도달)
    @Test fun append50thEntryTriggersFlush() = runTest {
        val sent = mutableListOf<List<SdkLogEntry>>()
        val buffer = SdkLogBuffer(send = { batch -> sent.add(batch); true }, scope = this)

        repeat(50) { buffer.append(entry("L$it")) }
        advanceUntilIdle()

        assertEquals(1, sent.size)
        assertEquals(50, sent[0].size)
        assertEquals(0, buffer.count)
    }

    // 2001건 → 가장 오래된 1건 버림 (hardLimit 2000). threshold 를 올려 자동 flush 가 끼어들지
    // 않게 한 뒤, flush 를 직접 호출해 남은 2000건이 [1..2000](0번이 버려짐)인지 확인한다.
    @Test fun appendBeyondHardLimitDropsOldest() = runTest {
        val sent = mutableListOf<List<SdkLogEntry>>()
        val buffer = SdkLogBuffer(threshold = Int.MAX_VALUE, send = { batch -> sent.add(batch); true }, scope = this)

        (0..2000).forEach { buffer.append(entry("L$it")) } // 2001건

        assertEquals(2000, buffer.count)

        buffer.flush()
        val all = sent.flatten()
        assertEquals(2000, all.size)
        assertEquals("L1", all.first().code) // L0 이 버려짐
        assertEquals("L2000", all.last().code)
    }

    // 전송 실패 → 배치 폐기 (좌표 버퍼처럼 유지하지 않는다)
    @Test fun flushDiscardsBatchOnSendFailure() = runTest {
        val buffer = SdkLogBuffer(threshold = Int.MAX_VALUE, send = { false }, scope = this)
        buffer.append(entry("E1"))
        buffer.append(entry("E2"))

        buffer.flush()

        assertEquals(0, buffer.count)
    }
}
