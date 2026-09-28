package co.onecheck.ones1ght.android.internal

import kotlinx.serialization.json.Json

/**
 * 서버와 주고받는 JSON 의 공용 설정.
 *
 * - `explicitNulls = false`: null 필드는 인코딩에서 생략한다(보낸 필드만 갱신 규칙).
 * - `ignoreUnknownKeys = true`: 서버가 모르는 필드를 늘려도 디코딩이 깨지지 않는다.
 * - `encodeDefaults = true`: 기본값이 있는 필드도(null 이 아니면) 항상 인코딩한다.
 */
internal val SdkJson: Json = Json {
    explicitNulls = false
    ignoreUnknownKeys = true
    encodeDefaults = true
}
