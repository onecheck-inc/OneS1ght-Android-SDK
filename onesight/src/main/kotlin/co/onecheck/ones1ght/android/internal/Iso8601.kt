package co.onecheck.ones1ght.android.internal

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * 시각 형식 — ISO-8601 UTC 밀리초 (`yyyy-MM-dd'T'HH:mm:ss.SSS'Z'`).
 */
internal object Iso8601 {
    private val formatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

    internal fun format(epochMillis: Long): String = formatter.format(Instant.ofEpochMilli(epochMillis))

    /** [clock] 이 준 현재 시각(epochMillis)을 그대로 포맷한다 — 테스트가 시계를 주입할 수 있게. */
    internal fun nowString(clock: () -> Long): String = format(clock())
}
