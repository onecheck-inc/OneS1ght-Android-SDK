package co.onecheck.ones1ght.android.internal

import kotlinx.serialization.json.Json

/**
 * 서버와 주고받는 JSON 의 공용 설정.
 *
 * - `explicitNulls = false`: null 필드는 인코딩에서 생략한다(보낸 필드만 갱신 규칙).
 * - `ignoreUnknownKeys = true`: 서버가 모르는 필드를 늘려도 디코딩이 깨지지 않는다.
 * - `encodeDefaults = true`: 기본값이 있는 필드도(null 이 아니면) 항상 인코딩한다.
 * - `coerceInputValues = true`: 기본값이 있는 필드에 null·모르는 enum 값이 오면 기본값으로 읽는다 —
 *   선택 필드 하나 때문에 응답 전체가 실패하지 않게(감사 SF-A5 · iOS S17). 기본값 없는 필수 필드는 그대로 엄격하다.
 */
@get:JvmSynthetic // 최상위 internal 프로퍼티의 getter 는 Java 에 보인다
internal val SdkJson: Json = Json {
    explicitNulls = false
    ignoreUnknownKeys = true
    encodeDefaults = true
    coerceInputValues = true
}
