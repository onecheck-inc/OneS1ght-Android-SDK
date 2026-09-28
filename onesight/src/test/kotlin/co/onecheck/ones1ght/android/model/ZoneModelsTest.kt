package co.onecheck.ones1ght.android.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [Zone.contains] (ray casting) · [ZoneEvent] id/label 형식 · [Floor] max 계산 속성 검증.
 * 포팅 원본: ZoneEngine.swift (Position·Zone·ZoneEvent 부분만).
 */
class ZoneModelsTest {

    private val square = Zone(
        id = "z1",
        name = "정사각형",
        polygon = listOf(
            Position(0.0, 0.0),
            Position(10.0, 0.0),
            Position(10.0, 10.0),
            Position(0.0, 10.0),
        ),
    )

    @Test fun containsInsideSquare() {
        assertTrue(square.contains(Position(5.0, 5.0)))
    }

    @Test fun containsOutsideSquare() {
        assertFalse(square.contains(Position(15.0, 5.0)))
    }

    @Test fun containsFalseWhenFewerThanThreeVertices() {
        val line = Zone(id = "z2", name = "선", polygon = listOf(Position(0.0, 0.0), Position(1.0, 1.0)))
        assertFalse(line.contains(Position(0.5, 0.5)))
    }

    @Test fun enterEventIdAndLabelFormat() {
        val zone = Zone(id = "z9", name = "입구존", polygon = emptyList())
        val e = ZoneEvent.Enter(zone, at = 5000L)
        assertEquals("in-z9-5", e.id)
        assertEquals("IN  · 입구존", e.label)
    }

    @Test fun exitEventIdAndLabelFormat() {
        val zone = Zone(id = "z9", name = "입구존", polygon = emptyList())
        val e = ZoneEvent.Exit(zone, at = 5000L)
        assertEquals("out-z9-5", e.id)
        assertEquals("OUT · 입구존", e.label)
    }

    @Test fun dwellEventIdAndLabelFormat() {
        val zone = Zone(id = "z9", name = "입구존", polygon = emptyList())
        val e = ZoneEvent.Dwell(zone, seconds = 15.0, at = 15000L)
        assertEquals("dw-z9-15-15", e.id)
        assertEquals("DWELL · 입구존 (15s)", e.label)
    }

    @Test fun floorMaxComputedFromOriginAndSize() {
        val floor = Floor(id = "f1", name = "1F", originX = 2.0, originY = 3.0, widthM = 10.0, heightM = 20.0)
        assertEquals(2.0, floor.minX, 0.0)
        assertEquals(3.0, floor.minY, 0.0)
        assertEquals(12.0, floor.maxX, 0.0)
        assertEquals(23.0, floor.maxY, 0.0)
    }

    @Test fun floorEqualityComparesImageByContent() {
        val a = Floor(id = "f1", name = "1F", image = byteArrayOf(1, 2, 3))
        val b = Floor(id = "f1", name = "1F", image = byteArrayOf(1, 2, 3))
        val c = Floor(id = "f1", name = "1F", image = byteArrayOf(1, 2, 4))
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertFalse(a == c)
    }
}
