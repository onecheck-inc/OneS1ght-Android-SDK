package co.onecheck.ones1ght.android.model

import co.onecheck.ones1ght.android.internal.SdkJson
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * 콘솔에서 바뀐 것 — 고객사에게 알리는 형태(`FloorSession.onConfigChanged` 로 전달된다).
 *
 * ⚠️ 이벤트는 얇다. "무엇이 바뀌었다"만 오고 값은 오지 않는다(rate_hz 처럼 작은 값은 예외).
 * 무엇을 다시 받을지는 고객사가 정한다 — SDK 는 대신 정하지 않는다.
 *
 * 파싱(`ConfigChange.parse`)은 이 타입을 쓰는 쪽(Task 6, SSE 프레임 해석)에서
 * `companion object` 확장 함수로 붙인다 — 여기는 데이터 모양만.
 *
 * 포팅 원본: ConfigChange.swift 의 `ConfigChange` enum(파싱 로직 제외).
 */
public sealed class ConfigChange {
    /** 구역이 생기거나 바뀌거나 사라졌다. */
    public data class ZonesChanged(public val floorId: String?) : ConfigChange()

    /** 층 도면이 바뀌었다. */
    public data class PlanChanged(public val floorId: String?) : ConfigChange()

    /** 시책 상태가 바뀌었다(실행·활성화·연결·중지). */
    public data class RulesChanged(public val zoneId: String?) : ConfigChange()

    /** 원격 설정이 바뀌었다. 값이 직접 실려 온다. */
    public data class SdkConfigChanged(public val rateHz: Int?, public val logLevel: String?) : ConfigChange()

    /**
     * 연결이 (재)수립됐거나 이벤트를 놓쳤다 — 그 사이에 무엇이든 바뀌었을 수 있다.
     * 지금 쓰고 있는 것을 통째로 다시 받아야 한다.
     */
    public data object ResyncNeeded : ConfigChange()

    public companion object
}

/**
 * SSE 프레임(`event`/`data`) 한 건을 신호로 바꾼다. JSON 이 깨졌으면 null(그 한 건만 버린다).
 *
 * ⚠️ **모르는 `event` 타입도 null 이 아니라 `change == null` 로 돌려준다.** 서버가 새 타입을
 * 늘렸을 때 seq 추적이 끊기면 다음 이벤트에서 갭 오탐이 나 불필요한 재동기화가 돈다.
 *
 * 반환값은 (seq, change) — seq 는 갭 판정(Task 6, [co.onecheck.ones1ght.android.runtime.LiveConfigStream])에 쓴다.
 *
 * 포팅 원본: ConfigChange.swift 의 `LiveSignal.parse`.
 */
internal fun ConfigChange.Companion.parse(event: String, data: String): Pair<Int?, ConfigChange?>? {
    val element = try {
        SdkJson.parseToJsonElement(data)
    } catch (e: SerializationException) {
        return null
    }
    val obj = element as? JsonObject ?: return null

    val seq = (obj["seq"] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
    val floorId = (obj["floor_id"] as? JsonPrimitive)?.takeIf { it.isString }?.content

    val change = when (event) {
        "zones.changed" -> ConfigChange.ZonesChanged(floorId = floorId)
        "plan.changed" -> ConfigChange.PlanChanged(floorId = floorId)
        // ⚠️ zone_id 는 콘솔에서 숫자로 온다 — 문자열 필드는 아니지만 값은 그대로 문자열화한다
        // (JSON null 은 제외 — contentOrNull 이 처리).
        "rules.changed" -> ConfigChange.RulesChanged(zoneId = (obj["zone_id"] as? JsonPrimitive)?.contentOrNull)
        "sdk.config.changed" -> ConfigChange.SdkConfigChanged(
            rateHz = (obj["rate_hz"] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull,
            logLevel = (obj["log_level"] as? JsonPrimitive)?.takeIf { it.isString }?.content,
        )
        else -> null // hello 포함 — 기준선만 갱신
    }
    return seq to change
}
