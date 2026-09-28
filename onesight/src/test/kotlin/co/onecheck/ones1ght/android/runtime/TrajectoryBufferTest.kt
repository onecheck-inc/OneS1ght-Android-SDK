package co.onecheck.ones1ght.android.runtime

import co.onecheck.ones1ght.android.model.Coordinates
import co.onecheck.ones1ght.android.model.PositionPoint
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 좌표 버퍼 — 상한 분할 전송 · 실패 시 유지. 포팅 원본: TrajectoryBufferTests.swift.
 */
class TrajectoryBufferTest {

    private fun point(i: Int): PositionPoint =
        PositionPoint(floorId = "F", coordinates = Coordinates(i.toDouble(), 0.0, 0.0), capturedAt = "t$i")

    // maxBatch 단위로 잘라 여러 번 전송 (5개, 상한2 → 2+2+1)
    @Test fun flushChunksByMaxBatch() = runTest {
        val buffer = TrajectoryBuffer(maxBatch = 2)
        val batches = mutableListOf<List<PositionPoint>>()
        (1..5).forEach { buffer.append(point(it)) }

        val result = buffer.flush { batch -> batches.add(batch); true }

        assertEquals(listOf(2, 2, 1), batches.map { it.size })
        assertEquals(0, buffer.count)
        assertTrue(result)
    }

    // 전송 실패 시 남은 건 유지 (첫 배치 성공, 둘째 실패 → 3개 남음)
    @Test fun flushKeepsRemainderOnFailure() = runTest {
        var calls = 0
        val buffer = TrajectoryBuffer(maxBatch = 2)
        (1..5).forEach { buffer.append(point(it)) }

        val firstResult = buffer.flush { calls += 1; calls == 1 } // 첫 번째만 성공
        assertEquals(3, buffer.count) // 2개만 제거, 3개 유지
        assertFalse(firstResult)

        val secondResult = buffer.flush { calls += 1; calls == 1 } // 이번엔 실패 → 그대로
        assertEquals(3, buffer.count)
        assertFalse(secondResult)
    }

    // 1200건 → 500/500/200
    @Test fun flushChunksLargeBatchByDefaultMaxBatch500() = runTest {
        val buffer = TrajectoryBuffer()
        val batches = mutableListOf<List<PositionPoint>>()
        (1..1200).forEach { buffer.append(point(it)) }

        val result = buffer.flush { batch -> batches.add(batch); true }

        assertEquals(listOf(500, 500, 200), batches.map { it.size })
        assertEquals(0, buffer.count)
        assertTrue(result)
    }

    // 두 번째 배치가 실패하면 700건이 남는다 (1200 - 500)
    @Test fun flushSecondBatchFailureLeaves700() = runTest {
        var calls = 0
        val buffer = TrajectoryBuffer()
        (1..1200).forEach { buffer.append(point(it)) }

        val result = buffer.flush { calls += 1; calls == 1 } // 첫 배치만 성공

        assertEquals(700, buffer.count)
        assertFalse(result)
    }

    // 재진입 방지 — flush 도중 다시 flush 를 부르면 false
    @Test fun flushReturnsFalseWhenReentrant() = runTest {
        val buffer = TrajectoryBuffer(maxBatch = 1)
        (1..2).forEach { buffer.append(point(it)) }
        var reentrantResult: Boolean? = null

        buffer.flush { batch ->
            if (reentrantResult == null) {
                reentrantResult = buffer.flush { true }
            }
            true
        }

        assertEquals(false, reentrantResult)
    }

    @Test fun clearEmptiesWithoutSending() {
        val buffer = TrajectoryBuffer()
        buffer.append(point(1))
        buffer.append(point(2))

        buffer.clear()

        assertEquals(0, buffer.count)
    }
}
