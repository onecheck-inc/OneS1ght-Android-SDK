package co.onecheck.ones1ght.android.model

//
//  Dtos.kt
//  서버 계약 데이터 구조 — sdk-v1-사양서 §7.1 그대로 (유일한 근거)
//
//  내부 전용 DTO 는 필드명을 서버 JSON 과 똑같은 snake_case 로 그대로 쓴다(iOS DTOs.swift 와
//  동일 방침 — CodingKeys/SerialName 없이 1:1). 고객에게 노출되는 공개 타입(Trigger 등)만
//  카멜케이스 + @SerialName 을 쓴다. 여기는 "데이터 모양"만 — 로직 0.
//

import co.onecheck.ones1ght.android.internal.LenientStringMapSerializer
import co.onecheck.ones1ght.android.internal.parseLenientStringMap
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.element
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

// MARK: - 공용

/** 좌표 (미터 — 측위 엔진 원본 단위. 2D면 z=0) */
@Serializable
public data class Coordinates(
    public val x: Double,
    public val y: Double,
    public val z: Double,
)

/** 존 이벤트 상태 — 서버 표기는 IN/DWELL/OUT. */
public enum class ZoneEventStatus(public val wire: String) {
    ENTER("IN"),
    DWELL("DWELL"),
    EXIT("OUT"),
}

/**
 * POST /events/zone 응답 — triggers = 서버가 매칭한 개인화 액션(없으면 빈 리스트).
 *
 * ⚠️ [payload] 는 `remote_config` 와 완전히 같은 구조다 — 여기서 실패하면 안 된다
 * ([LenientStringMapSerializer] 로 최선노력 읽기).
 */
@Serializable
public data class Trigger(
    @SerialName("trigger_id") public val triggerId: String,
    /** signage | coupon | tracking | merch | generic */
    public val type: String,
    @Serializable(LenientStringMapSerializer::class) public val payload: Map<String, String>? = null,
)

/** 서버가 값을 주지 않을 때 쓰는 기본값 — 종전 SDK 하드코딩과 같아 동작이 바뀌지 않는다. */
public object SdkDefaults {
    public const val POSITION_RATE_HZ: Int = 4
    public const val MIN_RATE_HZ: Int = 1
    public const val MAX_RATE_HZ: Int = 100
}

// MARK: - 요청 (SDK → 서버)

/** verify 의 client 블록 — 필수는 profile_id 뿐, 나머지는 "가진 것만"(생략 시 서버 기존값 보존). */
@Serializable
internal data class ClientInfo(
    val profile_id: String,
    val device_model: String? = null,
    val os_name: String? = null,
    val os_version: String? = null,
    val app_version: String? = null,
    val sdk_version: String? = null,
    val device_language: String? = null,
    val attributes: Map<String, String>? = null,
)

/** POST /auth/verify — 키 검증 + 클라 등록(초기화 1회). */
@Serializable
internal data class ReqVerify(
    @SerialName("platform_name") val platformName: String,
    @SerialName("app_id") val appId: String? = null,
    val client: ClientInfo? = null,
)

/** POST /events/zone — 존 입장/체류/퇴장(판정 시마다). */
@Serializable
internal data class ReqZoneEvent(
    val profile_id: String,
    val visitor_id: String,
    val floor_id: String,
    val zone_id: String,
    val status: String,
    val occurred_at: String,
    val platform_name: String,
)

/** /positioning/logs 의 points[] 요소. */
@Serializable
internal data class PositionPoint(
    val floor_id: String,
    val coordinates: Coordinates,
    val captured_at: String,
)

/** POST /positioning/logs — 좌표 벌크(봉투 1회 + points[] 반복, 요청당 ≤500). */
@Serializable
internal data class ReqPositionBulk(
    val profile_id: String,
    val visitor_id: String,
    val platform_name: String,
    val points: List<PositionPoint>,
)

/** POST /profiles 요청 — 속성은 고객사 자유(성별·연령대·관심사 등). */
@Serializable
internal data class ReqProfile(val attributes: Map<String, String>)

/** 로그 한 줄. 문구가 아니라 코드를 보낸다. */
@Serializable
internal data class SdkLogEntry(
    val code: String,
    val level: String,
    val message: String,
    val at: String,
)

/** POST /logs — 요청당 최대 500건(초과 시 422). */
@Serializable
internal data class ReqSdkLogs(
    val profile_id: String,
    val platform_name: String,
    val sdk_version: String,
    val entries: List<SdkLogEntry>,
)

// MARK: - 응답 (서버 → SDK)

/**
 * POST /auth/verify 응답.
 *
 * ⚠️ [tenantCode]·[positionRateHz] 는 타입이 어긋나도 초기화를 막지 않도록 null 로
 * 떨어져야 한다(iOS `try?` 와 동일). [ResVerifySerializer] 가 JsonObject 를 직접 받아
 * 필드마다 개별적으로 시도한다. [remoteConfig] 도 같은 이유로 실패해도 초기화를 막지 않는다
 * ([LenientStringMapSerializer]).
 */
@Serializable(with = ResVerifySerializer::class)
internal data class ResVerify(
    val valid: Boolean,
    @SerialName("positioning_enabled") val positioningEnabled: Boolean,
    @SerialName("tenant_code") val tenantCode: String? = null,
    @SerialName("position_rate_hz") val positionRateHz: Int? = null,
    @SerialName("remote_config") val remoteConfig: Map<String, String>? = null,
)

/**
 * [ResVerify] 전용 커스텀 시리얼라이저 — JsonObject 를 직접 받아 [tenantCode]·
 * [positionRateHz]·[remoteConfig] 를 최선노력으로 읽는다(실패해도 null/빈 값으로 떨어질 뿐
 * 전체 디코딩은 계속된다). [valid]·[positioningEnabled] 는 측위 가부를 가르는 값이라
 * 여기는 엄격하게 — 없거나 타입이 어긋나면 디코딩 자체가 실패하는 게 맞다.
 */
internal object ResVerifySerializer : KSerializer<ResVerify> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("ResVerify") {
        element<Boolean>("valid")
        element<Boolean>("positioning_enabled")
        element<String>("tenant_code", isOptional = true)
        element<Int>("position_rate_hz", isOptional = true)
        element("remote_config", LenientStringMapSerializer.descriptor, isOptional = true)
    }

    override fun deserialize(decoder: Decoder): ResVerify {
        val jsonDecoder = decoder as? JsonDecoder
            ?: throw SerializationException("ResVerify 는 JSON 디코더에서만 쓸 수 있다")
        val obj = jsonDecoder.decodeJsonElement().jsonObject

        val valid = (obj["valid"] as? JsonPrimitive)?.booleanOrNull
            ?: throw SerializationException("ResVerify.valid 가 없거나 boolean 이 아니다")
        val positioningEnabled = (obj["positioning_enabled"] as? JsonPrimitive)?.booleanOrNull
            ?: throw SerializationException("ResVerify.positioning_enabled 가 없거나 boolean 이 아니다")

        val tenantCode = (obj["tenant_code"] as? JsonPrimitive)
            ?.takeIf { it.isString }
            ?.content

        val positionRateHz = (obj["position_rate_hz"] as? JsonPrimitive)
            ?.takeIf { !it.isString }
            ?.intOrNull

        val remoteConfig = obj["remote_config"]?.let { parseLenientStringMap(it) }

        return ResVerify(
            valid = valid,
            positioningEnabled = positioningEnabled,
            tenantCode = tenantCode,
            positionRateHz = positionRateHz,
            remoteConfig = remoteConfig,
        )
    }

    override fun serialize(encoder: Encoder, value: ResVerify) {
        val jsonEncoder = encoder as? JsonEncoder
            ?: throw SerializationException("ResVerify 는 JSON 인코더에서만 쓸 수 있다")
        val json = buildJsonObject {
            put("valid", value.valid)
            put("positioning_enabled", value.positioningEnabled)
            value.tenantCode?.let { put("tenant_code", it) }
            value.positionRateHz?.let { put("position_rate_hz", it) }
            value.remoteConfig?.let { map ->
                put("remote_config", buildJsonObject { map.forEach { (k, v) -> put(k, v) } })
            }
        }
        jsonEncoder.encodeJsonElement(json)
    }
}

/**
 * 콘솔이 내려주는 관련 키 — SDK 키 하나로 받는다. 전부 옵셔널이다. 서버는 채우지 못한 키를
 * null 로 두고 200 을 준다(부분 실패) — 하나를 필수로 만들면 그 키가 빈 테넌트에서
 * 초기화가 통째로 실패한다.
 */
@Serializable
internal data class ResSdkConfig(
    val tenant_code: String? = null,
    val google_map_key: String? = null,
    val geo_sdk_key: String? = null,
    val geo_partner_key: String? = null,
    val geo_base_url: String? = null,
)

/** GET /positioning/buildings 응답의 층 항목. */
@Serializable
internal data class FloorRef(
    val floor_id: String,
    val name: String,
)

/** GET /positioning/buildings 응답의 건물 항목. */
@Serializable
internal data class BuildingRef(
    val building_id: String,
    val name: String,
    val store_id: Int? = null,
    /** 서버가 생략 가능 → provider 하드코딩 floorId 폴백. */
    val floors: List<FloorRef>? = null,
)

/** GET /positioning/buildings 응답. */
@Serializable
internal data class ResBuildings(
    val synced_at: String,
    val buildings: List<BuildingRef>,
)

/** GET /positioning/floors/{floor_id} 응답의 존 판정 파라미터 9종. */
@Serializable
internal data class ZoneMeta(
    val zone_id: String,
    val name: String,
    /** 미터 좌표 다각형. */
    val polygon: List<List<Double>>? = null,
    val trigger_type: String,
    val dwell_seconds: Int? = null,
    val in_dist: Double,
    val in_count: Int,
    val in_count_interval: Int,
    val out_period: Int,
    val priority: Int,
    val call_inout: Boolean,
    val is_active: Boolean,
)

/** GET /positioning/floors/{floor_id} 응답. */
@Serializable
internal data class ResFloorConfig(
    val floor_id: String,
    val building_id: String? = null,
    val name: String,
    val synced_at: String,
    val zones: List<ZoneMeta>,
    /** 현재 항상 빈 리스트(공간 서비스 앵커 API 대기). */
    val anchors: List<String>,
)

/** POST /events/zone 응답. */
@Serializable
internal data class ResZoneEvent(
    val accepted: Boolean,
    val event_id: String,
    val triggers: List<Trigger>,
)

/** POST /positioning/logs 응답. */
@Serializable
internal data class ResPositionBulk(val accepted_count: Int)

// MARK: - 프로필 (서버 TBD — SDK 가 계약을 정의한다)

/** POST /profiles 응답 — 서버가 profileId 를 발급한다. */
@Serializable
internal data class ResProfileCreate(val profile_id: String)

/** GET·PUT /profiles/{id} 응답. */
@Serializable
internal data class ResProfile(
    val profile_id: String,
    val attributes: Map<String, String>? = null,
)

/** DELETE /profiles/{id} 응답. */
@Serializable
internal data class ResProfileDelete(val deleted: Boolean)

/** POST /logs 응답. */
@Serializable
internal data class ResSdkLogs(val accepted_count: Int)
