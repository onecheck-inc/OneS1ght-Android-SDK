package co.onecheck.ones1ght.android.positioning

import co.onecheck.ones1ght.android.model.Coordinates
import co.onecheck.ones1ght.android.model.ZoneEventStatus
import co.onecheck.ones1ght.android.runtime.SdkErrorCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 테스트/데모용 가짜 측위 — 콜백을 프로그램적으로 발생시켜 SDK 파이프라인을 검증한다.
 *
 * 포팅 원본: MockPositioningProvider.swift.
 */
class MockPositioningProviderTest {

    private class RecordingDelegate : PositioningProviderDelegate {
        val positions = mutableListOf<Triple<Coordinates, String?, Long>>()
        val zones = mutableListOf<Any>()
        val enters = mutableListOf<String>()
        val reports = mutableListOf<Pair<SdkErrorCode, String>>()

        override fun onPosition(provider: PositioningProvider, coordinates: Coordinates, floorId: String?, atMs: Long) {
            positions.add(Triple(coordinates, floorId, atMs))
        }

        override fun onZone(provider: PositioningProvider, zoneId: String, status: ZoneEventStatus, floorId: String?, atMs: Long) {
            zones.add(listOf(zoneId, status, floorId, atMs))
        }

        override fun onEnter(provider: PositioningProvider, buildingId: String) {
            enters.add(buildingId)
        }

        override fun onReport(provider: PositioningProvider, code: SdkErrorCode, context: String) {
            reports.add(code to context)
        }
    }

    @Test fun startSetsRunningTrue() {
        val mock = MockPositioningProvider()
        assertFalse(mock.isRunning)

        mock.start()

        assertTrue(mock.isRunning)
    }

    @Test fun stopSetsRunningFalse() {
        val mock = MockPositioningProvider()
        mock.start()

        mock.stop()

        assertFalse(mock.isRunning)
    }

    @Test fun applyBuildingAndFloorStoresBoth() {
        val mock = MockPositioningProvider()

        mock.apply(buildingId = "b1", floorId = "f1")

        assertEquals("b1", mock.appliedBuildingId)
        assertEquals("f1", mock.appliedFloorId)
    }

    @Test fun applyConfigStoresConfig() {
        val mock = MockPositioningProvider()
        val config = PositioningConfig(
            anchors = mapOf(0xABCD to doubleArrayOf(1.0, 2.0, 0.0)),
            sessionId = 7,
        )

        mock.apply(config)

        assertEquals(config, mock.appliedConfig)
    }

    @Test fun reloadGeofencesIncrementsCount() {
        val mock = MockPositioningProvider()
        assertEquals(0, mock.reloadGeofencesCount)

        mock.reloadGeofences()
        mock.reloadGeofences()

        assertEquals(2, mock.reloadGeofencesCount)
    }

    @Test fun simulateEnterCallsDelegateOnEnter() {
        val mock = MockPositioningProvider()
        val delegate = RecordingDelegate()
        mock.delegate = delegate

        mock.simulateEnter("b1")

        assertEquals(listOf("b1"), delegate.enters)
    }

    @Test fun simulatePositionCallsDelegateOnPosition() {
        val mock = MockPositioningProvider()
        val delegate = RecordingDelegate()
        mock.delegate = delegate
        val coords = Coordinates(1.0, 2.0, 0.0)

        mock.simulatePosition(coords, "f1", 1000L)

        assertEquals(1, delegate.positions.size)
        assertEquals(coords, delegate.positions[0].first)
        assertEquals("f1", delegate.positions[0].second)
        assertEquals(1000L, delegate.positions[0].third)
    }

    @Test fun simulateZoneCallsDelegateOnZone() {
        val mock = MockPositioningProvider()
        val delegate = RecordingDelegate()
        mock.delegate = delegate

        mock.simulateZone("z1", ZoneEventStatus.ENTER, "f1", 2000L)

        assertEquals(1, delegate.zones.size)
        assertEquals(listOf("z1", ZoneEventStatus.ENTER, "f1", 2000L), delegate.zones[0])
    }

    @Test fun simulationsAreNoopsWithoutDelegate() {
        // delegate 를 안 붙였을 때도 예외 없이 조용히 넘어가야 한다.
        val mock = MockPositioningProvider()

        mock.simulateEnter("b1")
        mock.simulatePosition(Coordinates(0.0, 0.0, 0.0), null, 0L)
        mock.simulateZone("z1", ZoneEventStatus.EXIT, null, 0L)
    }

    @Test fun defaultsHaveNoDiagnosticAndNotPaused() {
        val mock = MockPositioningProvider()
        assertNull(mock.positioningDiagnostic)
        assertFalse(mock.isPaused)
    }

    // MARK: - PositioningConfig 동등성 (DoubleArray 내용 비교, 참조 비교 아님)

    @Test fun positioningConfigEqualsComparesAnchorArraysByContent() {
        val a = PositioningConfig(anchors = mapOf(1 to doubleArrayOf(1.0, 2.0, 3.0)), sessionId = 5)
        val b = PositioningConfig(anchors = mapOf(1 to doubleArrayOf(1.0, 2.0, 3.0)), sessionId = 5)

        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test fun positioningConfigNotEqualWhenAnchorContentDiffers() {
        val a = PositioningConfig(anchors = mapOf(1 to doubleArrayOf(1.0, 2.0, 3.0)))
        val b = PositioningConfig(anchors = mapOf(1 to doubleArrayOf(9.0, 9.0, 9.0)))

        assertFalse(a == b)
    }

    @Test fun positioningConfigHashStableRegardlessOfMapIterationOrder() {
        val a = PositioningConfig(anchors = linkedMapOf(1 to doubleArrayOf(1.0, 0.0, 0.0), 2 to doubleArrayOf(2.0, 0.0, 0.0)))
        val b = PositioningConfig(anchors = linkedMapOf(2 to doubleArrayOf(2.0, 0.0, 0.0), 1 to doubleArrayOf(1.0, 0.0, 0.0)))

        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test fun positioningDiagnosticMissingLabelFormatsAsHex() {
        val diag = PositioningDiagnostic(
            registeredCount = 4,
            receivedCount = 3,
            matchedCount = 3,
            missingAddresses = listOf(0xABCD, 0x42),
            hasFix = true,
        )

        assertEquals("0xABCD,0x0042", diag.missingLabel)
    }
}
