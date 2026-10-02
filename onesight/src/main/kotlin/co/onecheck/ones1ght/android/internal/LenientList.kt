package co.onecheck.ones1ght.android.internal

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement

/**
 * 서버 목록을 **요소 단위로** 읽는다 — 항목 하나가 깨져도(필수 필드 null·타입 어긋남·객체 아님) 그 항목만
 * 버리고 나머지는 산다. 목록 자체가 배열이 아니면 그건 응답 전체가 틀린 것이라 던진다.
 *
 * 왜: 목록 DTO 를 엄격하게 읽으면 구역 하나의 `name:null` 이 구역 목록 전체를 실패시켜, 층을 열어도
 * 구역 0개(E3004)가 되고 시책이 통째로 안 돌았다(감사 SF-A5 · iOS S17 — 서버가 늘릴 수 있는 필드는
 * 관대하게).
 *
 * @param onDrop 버린 항목의 위치·사유 — 진단 로그용. 이 함수는 로그를 직접 남기지 않는다.
 */
@JvmSynthetic // 최상위 internal 함수는 이름이 망글링되지 않아 Java 에 보인다
internal fun <T> decodeLenientList(
    elements: List<JsonElement>,
    serializer: KSerializer<T>,
    onDrop: (index: Int, element: JsonElement, reason: String) -> Unit = { _, _, _ -> },
): List<T> = elements.mapIndexedNotNull { i, el ->
    try {
        SdkJson.decodeFromJsonElement(serializer, el)
    } catch (e: IllegalArgumentException) { // SerializationException 도 여기 포함된다
        onDrop(i, el, e.message?.lineSequence()?.firstOrNull().orEmpty())
        null
    }
}

/**
 * [decodeLenientList] 의 시리얼라이저 판 — DTO 필드에 `@Serializable(with = …)` 로 단다. 버린 항목은 알리지
 * 않는다(알려야 하는 목록은 `List<JsonElement>` 로 받아 호출부에서 [decodeLenientList] 를 부른다).
 */
internal open class LenientListSerializer<T>(private val element: KSerializer<T>) : KSerializer<List<T>> {
    private val strict = ListSerializer(element)

    override val descriptor: SerialDescriptor = strict.descriptor

    override fun deserialize(decoder: Decoder): List<T> {
        val json = decoder as? JsonDecoder
            ?: throw SerializationException("LenientListSerializer 는 JSON 디코더에서만 쓸 수 있다")
        val array = json.decodeJsonElement() as? JsonArray
            ?: throw SerializationException("목록 자리에 배열이 아닌 값이 왔다")
        return decodeLenientList(array, element)
    }

    override fun serialize(encoder: Encoder, value: List<T>) = strict.serialize(encoder, value)
}
