package co.onecheck.ones1ght.android.runtime

//
//  GeofenceReloadTest.kt
//  구역이 바뀌면 판정 엔진이 그 사실을 알게 되는가 — "무엇을 바뀌었다고 볼 것인가"(id 집합 비교).
//  포팅 원본: GeofenceReloadTests.swift.
//

import co.onecheck.ones1ght.android.model.Position
import co.onecheck.ones1ght.android.model.Zone
import co.onecheck.ones1ght.android.positioning.MockPositioningProvider
import co.onecheck.ones1ght.android.positioning.PositioningProvider
import co.onecheck.ones1ght.android.positioning.PositioningProviderDelegate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeofenceReloadTest {

    private fun zone(id: String) =
        Zone(id, "z-$id", listOf(Position(0.0, 0.0), Position(1.0, 0.0), Position(1.0, 1.0)))

    // MARK: - 무엇을 "바뀌었다" 로 볼 것인가

    @Test fun 구역이_늘면_다시_읽어야_한다() {
        assertTrue(SessionCoordinator.geofencesChanged(listOf(zone("a")), listOf(zone("a"), zone("b"))))
    }

    @Test fun 구역이_줄면_다시_읽어야_한다() {
        assertTrue(SessionCoordinator.geofencesChanged(listOf(zone("a"), zone("b")), listOf(zone("a"))))
    }

    /** ⚠️ 구역을 다시 그리면 콘솔이 새 id 를 준다 — 이름이 같아도 엔진은 다시 읽어야 한다. */
    @Test fun 같은_이름이라도_id_가_바뀌면_다시_읽어야_한다() {
        assertTrue(SessionCoordinator.geofencesChanged(listOf(zone("020461cd")), listOf(zone("258dae1e"))))
    }

    /** 순서만 다른 것을 변경으로 보면 폴링마다 엔진이 재시작된다. */
    @Test fun 같은_구역이면_건드리지_않는다() {
        assertFalse(
            "순서만 다른 것을 변경으로 보면 폴링마다 엔진이 재시작된다",
            SessionCoordinator.geofencesChanged(listOf(zone("a"), zone("b")), listOf(zone("b"), zone("a"))),
        )
    }

    @Test fun 없다가_생기면_다시_읽어야_한다() {
        assertTrue(SessionCoordinator.geofencesChanged(emptyList(), listOf(zone("a"))))
    }

    @Test fun 전부_지우면_다시_읽어야_한다() {
        assertTrue(SessionCoordinator.geofencesChanged(listOf(zone("a")), emptyList()))
    }

    // MARK: - 프로바이더가 실제로 통보를 받는가

    /** 엔진이 없는 구현은 기본 no-op 이어야 한다 — 인터페이스 기본 구현. */
    @Test fun 엔진이_없는_프로바이더는_무시한다() {
        val bare = object : PositioningProvider {
            override var delegate: PositioningProviderDelegate? = null
            override fun start() {}
            override fun stop() {}
        }
        bare.reloadGeofences() // 기본 구현이 없으면 컴파일부터 안 된다
    }

    @Test fun Mock_은_통보를_기록한다() {
        val p = MockPositioningProvider()
        assertEquals(0, p.reloadGeofencesCount)
        p.reloadGeofences()
        p.reloadGeofences()
        assertEquals(2, p.reloadGeofencesCount)
    }
}
