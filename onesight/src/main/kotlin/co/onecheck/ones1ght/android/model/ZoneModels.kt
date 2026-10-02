package co.onecheck.ones1ght.android.model

/**
 * 도면 로컬 좌표 (미터 단위, 좌하단 원점).
 *
 * 포팅 원본: ZoneEngine.swift 의 `Position`.
 */
public data class Position(
    public val x: Double,
    public val y: Double,
)

/**
 * 구역 판정 파라미터의 기본값 — 서버(콘솔 존 메타 §6.4)가 값을 주지 않을 때 쓴다. [Zone] 생성자와 공간 조회가
 * 같은 값을 써야 한다(감사 SF-C5: 예전엔 세 곳에 따로 적혀 있었다). 포팅 원본: Zone.swift 의 `ZoneDefaults`.
 */
internal object ZoneDefaults {
    const val IN_DIST: Double = 3.0
    const val IN_COUNT: Int = 0
    const val IN_COUNT_INTERVAL: Int = 0
    const val OUT_PERIOD: Int = 0
    const val PRIORITY: Int = 1
    const val CALL_INOUT: Boolean = true
}

/**
 * 폴리곤 하나로 정의되는 구역.
 *
 * 포팅 원본: ZoneEngine.swift 의 `Zone`.
 */
public data class Zone @JvmOverloads constructor(
    public val id: String,
    public val name: String,
    public val polygon: List<Position>,
    // 판정 파라미터 — 콘솔 존 메타(§6.4)와 1:1.
    public val inDist: Double = ZoneDefaults.IN_DIST,
    public val inCount: Int = ZoneDefaults.IN_COUNT,
    public val inCountInterval: Int = ZoneDefaults.IN_COUNT_INTERVAL,
    public val outPeriod: Int = ZoneDefaults.OUT_PERIOD,
    public val priority: Int = ZoneDefaults.PRIORITY,
    public val callInout: Boolean = ZoneDefaults.CALL_INOUT,
    public val dwellSeconds: Int? = null,
) {
    /** ray casting — 점이 폴리곤 내부인가. 꼭짓점이 3개 미만이면 false. */
    public fun contains(p: Position): Boolean {
        if (polygon.size < 3) return false
        var inside = false
        var j = polygon.size - 1
        for (i in polygon.indices) {
            val a = polygon[i]
            val b = polygon[j]
            if ((a.y > p.y) != (b.y > p.y)) {
                val slope = (p.y - a.y) / (b.y - a.y)
                val xCross = a.x + slope * (b.x - a.x)
                if (p.x < xCross) inside = !inside
            }
            j = i
        }
        return inside
    }
}

/**
 * Zone 이벤트.
 *
 * `id` 형식: `"in-<zoneId>-<epochSec>"` / `"out-<zoneId>-<epochSec>"` /
 * `"dw-<zoneId>-<Int(seconds)>-<epochSec>"`.
 * `label` 형식: `"IN  · <name>"` / `"OUT · <name>"` / `"DWELL · <name> (Ns)"`.
 *
 * 포팅 원본: ZoneEngine.swift 의 `ZoneEvent`.
 */
public sealed class ZoneEvent {
    public abstract val zone: Zone
    public abstract val at: Long
    public abstract val id: String
    public abstract val label: String

    public data class Enter(
        override val zone: Zone,
        override val at: Long,
    ) : ZoneEvent() {
        override val id: String get() = "in-${zone.id}-${at / 1000}"
        override val label: String get() = "IN  · ${zone.name}"
    }

    public data class Exit(
        override val zone: Zone,
        override val at: Long,
    ) : ZoneEvent() {
        override val id: String get() = "out-${zone.id}-${at / 1000}"
        override val label: String get() = "OUT · ${zone.name}"
    }

    public data class Dwell(
        override val zone: Zone,
        public val seconds: Double,
        override val at: Long,
    ) : ZoneEvent() {
        override val id: String get() = "dw-${zone.id}-${seconds.toInt()}-${at / 1000}"
        override val label: String get() = "DWELL · ${zone.name} (${seconds.toInt()}s)"
    }
}
