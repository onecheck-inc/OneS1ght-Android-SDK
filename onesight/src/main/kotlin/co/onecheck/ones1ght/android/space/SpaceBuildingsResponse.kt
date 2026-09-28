package co.onecheck.ones1ght.android.space

//
//  SpaceBuildingsResponse.kt
//  공간 서비스 건물 트리 응답 — 경계에서 층 ID 를 문자열로 정규화한다.
//
//  ⚠️ 공간 서비스는 floorId 를 숫자로도 보낸다. 2026-08-20 에 같은 원인으로 콘솔 GCH 화면이
//     전부 500 이 났고, SDK 에도 같은 결함이 남아 있었다 — String 으로 선언해 두면 JSON
//     디코딩이 통째로 실패하고, 호출부가 실패를 삼키고 있어 오류가 사라진 채 빈 목록만
//     남는다. 증상은 "층이 안 뜬다" 뿐이라 원인에 닿기 어렵다.
//
//  숫자로 오든 문자열로 오든 문자열로 받는다 — 콘솔 /floors 가 주는 형태와 같아야
//  두 경로에서 온 층 ID 를 같은 값으로 다룰 수 있다.
//
//  포팅 원본: SpaceBuildingsResponse.swift.
//

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

/** 공간 서비스 `api/m/buildings` 응답(camelCase). */
@Serializable
internal data class SpaceBuildingsResponse(
    val buildings: List<SpaceBuildingDto>,
)

@Serializable
internal data class SpaceBuildingDto(
    val buildingId: String,
    val buildingName: String,
    val floors: List<SpaceFloorDto>,
)

@Serializable
internal data class SpaceFloorDto(
    @Serializable(with = FloorIdSerializer::class) val floorId: String,
    val floorName: String,
    val hasPlan: Boolean = false,
)

/**
 * [SpaceFloorDto.floorId] 전용 시리얼라이저 — JsonPrimitive 를 직접 받아 문자열이거나
 * 정수면 문자열로 정규화한다. 그 외 타입(불리언·null·객체·배열)이면 **던진다** — 그 층만
 * 조용히 버리면 이번과 같은 증상(빈 목록)이 다시 원인을 숨긴다. 형태가 또 바뀌면 시끄럽게
 * 깨지는 편이 낫다.
 */
internal object FloorIdSerializer : KSerializer<String> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("FloorId", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): String {
        val jsonDecoder = decoder as? JsonDecoder
            ?: throw SerializationException("FloorId 는 JSON 디코더에서만 쓸 수 있다")
        val element = jsonDecoder.decodeJsonElement()
        if (element is JsonPrimitive && element !is JsonNull) {
            if (element.isString) return element.content
            if (element.content.toLongOrNull() != null) return element.content
        }
        throw SerializationException(
            "floorId 가 문자열도 숫자도 아닙니다 — 공간 서비스 응답 형태가 바뀌었는지 확인하세요",
        )
    }

    override fun serialize(encoder: Encoder, value: String) {
        val jsonEncoder = encoder as? JsonEncoder
            ?: throw SerializationException("FloorId 는 JSON 인코더에서만 쓸 수 있다")
        jsonEncoder.encodeJsonElement(JsonPrimitive(value))
    }
}
