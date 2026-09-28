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
 * 폴리곤 하나로 정의되는 구역.
 *
 * 포팅 원본: ZoneEngine.swift 의 `Zone`.
 */
public data class Zone @JvmOverloads constructor(
    public val id: String,
    public val name: String,
    public val polygon: List<Position>,
    // 판정 파라미터 — 콘솔 존 메타(§6.4)와 1:1.
    public val inDist: Double = 3.0,
    public val inCount: Int = 0,
    public val inCountInterval: Int = 0,
    public val outPeriod: Int = 0,
    public val priority: Int = 1,
    public val callInout: Boolean = true,
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
