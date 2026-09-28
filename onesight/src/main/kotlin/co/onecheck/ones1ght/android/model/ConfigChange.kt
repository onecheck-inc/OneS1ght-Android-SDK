package co.onecheck.ones1ght.android.model

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
