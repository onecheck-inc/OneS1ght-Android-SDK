package co.onecheck.ones1ght.android.internal

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.mapSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 값 종류가 섞인 JSON 객체를 `Map<String, String>` 으로 접어 읽는다.
 *
 * 서버가 돌려주는 설정 자루(remote_config·trigger payload 등)는 문자열·정수·불리언이 함께
 * 온다. 좁은 타입으로 받으면 항목 하나가 늘 때마다 이미 나간 앱이 깨지므로, 넓게 받아
 * 문자열로 통일한다. 중첩 객체·배열·null 은 문자열로 옮길 마땅한 표현이 없어 건너뛴다 —
 * 빠뜨려도 초기화·이벤트 처리는 살아야 한다. 객체가 아니면 빈 맵.
 *
 * 포팅 원본: DTOs.swift 의 `LenientStringMap`.
 */
internal object LenientStringMapSerializer : KSerializer<Map<String, String>> {
    @OptIn(ExperimentalSerializationApi::class)
    override val descriptor: SerialDescriptor =
        mapSerialDescriptor(String.serializer().descriptor, String.serializer().descriptor)

    override fun deserialize(decoder: Decoder): Map<String, String> {
        val jsonDecoder = decoder as? JsonDecoder
            ?: throw SerializationException("LenientStringMapSerializer 는 JSON 디코더에서만 쓸 수 있다")
        return parseLenientStringMap(jsonDecoder.decodeJsonElement())
    }

    override fun serialize(encoder: Encoder, value: Map<String, String>) {
        val jsonEncoder = encoder as? JsonEncoder
            ?: throw SerializationException("LenientStringMapSerializer 는 JSON 인코더에서만 쓸 수 있다")
        jsonEncoder.encodeJsonElement(buildJsonObject { value.forEach { (k, v) -> put(k, v) } })
    }
}

/**
 * [element] 가 JSON 객체이면 각 값을 문자열로 접어 돌려준다(문자열·불리언·숫자만 — null·
 * 중첩 객체·배열은 건너뛴다). 객체가 아니면 빈 맵을 돌려준다(그래도 호출부는 계속돼야 한다).
 */
@JvmSynthetic // 최상위 internal 함수는 이름이 망글링되지 않아 Java 에 보인다
internal fun parseLenientStringMap(element: JsonElement): Map<String, String> {
    val obj = element as? JsonObject ?: return emptyMap()
    val out = LinkedHashMap<String, String>()
    for ((key, value) in obj) {
        if (value is JsonPrimitive && value !is JsonNull) {
            out[key] = value.content
        }
    }
    return out
}
