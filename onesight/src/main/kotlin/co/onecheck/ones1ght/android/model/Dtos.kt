package co.onecheck.ones1ght.android.model

//
//  Dtos.kt
//  서버 계약 데이터 구조 — sdk-v1-사양서 §7.1 그대로 (유일한 근거)
//
//  모든 DTO 는 카멜케이스 Kotlin 프로퍼티 + @SerialName("snake_case") 로 서버 JSON 과
//  1:1 매핑한다(대소문자 차이가 없는 단일 단어 필드는 @SerialName 없이 그대로 둔다).
//  와이어 JSON 은 항상 snake_case 그대로 — 테스트가 바이트 단위로 대조한다.
//  여기는 "데이터 모양"만 — 로직 0. 통신은 network/, 조립은 runtime/ 담당(다른 태스크).
//

import co.onecheck.ones1ght.android.internal.LenientListSerializer
import co.onecheck.ones1ght.android.internal.LenientStringMapSerializer
import co.onecheck.ones1ght.android.internal.parseLenientStringMap
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.element
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.encoding.decodeStructure
import kotlinx.serialization.encoding.encodeStructure
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

// MARK: - 공용

/**
 * 좌표 (미터 — 측위 엔진 원본 단위. 2D면 z=0)
 *
 * 공개 모델에는 @Serializable 을 달지 않는다 — 달면 `Coordinates.Companion.serializer()` 가
 * 공개 API 로 새어 kotlinx.serialization(implementation 의존) 타입이 고객에게 보인다.
 * 직렬화는 내부 DTO 쪽 [CoordinatesSerializer] 가 맡는다.
 */
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
 * ⚠️ [payload] 는 `remote_config` 와 완전히 같은 구조다 — 여기서 실패하면 안 된다.
 * 클래스 전체를 [TriggerSerializer] 가 직접 다뤄 [payload] 가 명시적 JSON `null` 이어도
 * (iOS `LenientStringMap` 처럼) 빈 맵으로 떨어지게 한다 — 키가 아예 없을 때만 null.
 */
// @Serializable 은 공개 클래스에 달지 않고 쓰는 자리(ResZoneEvent.triggers)에 단다 — Coordinates 와 같은 이유.
public data class Trigger @JvmOverloads constructor(
    /** 와이어 이름 trigger_id — 직렬화는 [TriggerSerializer] 가 직접 한다. */
    public val triggerId: String,
    /** signage | coupon | tracking | merch | generic */
    public val type: String,
    public val payload: Map<String, String>? = null,
)

/** [Coordinates] 직렬화 — {"x":..,"y":..,"z":..}. 공개 클래스 대신 쓰는 자리에서 지정한다. */
internal object CoordinatesSerializer : KSerializer<Coordinates> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("Coordinates") {
        element<Double>("x")
        element<Double>("y")
        element<Double>("z")
    }

    override fun serialize(encoder: Encoder, value: Coordinates) {
        encoder.encodeStructure(descriptor) {
            encodeDoubleElement(descriptor, 0, value.x)
            encodeDoubleElement(descriptor, 1, value.y)
            encodeDoubleElement(descriptor, 2, value.z)
        }
    }

    override fun deserialize(decoder: Decoder): Coordinates = decoder.decodeStructure(descriptor) {
        var x: Double? = null
        var y: Double? = null
        var z: Double? = null
        while (true) {
            when (val i = decodeElementIndex(descriptor)) {
                0 -> x = decodeDoubleElement(descriptor, 0)
                1 -> y = decodeDoubleElement(descriptor, 1)
                2 -> z = decodeDoubleElement(descriptor, 2)
                CompositeDecoder.DECODE_DONE -> break
                else -> throw SerializationException("Coordinates: 알 수 없는 인덱스 $i")
            }
        }
        Coordinates(
            x ?: throw SerializationException("Coordinates.x 없음"),
            y ?: throw SerializationException("Coordinates.y 없음"),
            z ?: throw SerializationException("Coordinates.z 없음"),
        )
    }
}

/**
 * [Trigger] 전용 커스텀 시리얼라이저 — [payload] 의 null 처리를 [ResVerifySerializer] 의
 * [ResVerify.remoteConfig] 와 똑같이 다룬다: 키가 아예 없으면 null, 있으면(JSON `null` 이든
 * 객체든 그 무엇이든) [parseLenientStringMap] 규칙으로 접는다(절대 throw 하지 않는다).
 *
 * 코틀린이 `Map<String,String>?` 프로퍼티에 `@Serializable(LenientStringMapSerializer::class)`
 * 를 자동으로 nullable 래핑하면 JSON `null` 이 시리얼라이저 호출 없이 곧장 Kotlin null 로
 * 빠져나가 iOS 동작(명시적 null → 빈 맵)과 어긋난다 — 그래서 클래스 전체를 직접 받는다.
 */
internal object TriggerSerializer : KSerializer<Trigger> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("Trigger") {
        element<String>("trigger_id")
        element<String>("type")
        element("payload", LenientStringMapSerializer.descriptor, isOptional = true)
    }

    override fun deserialize(decoder: Decoder): Trigger {
        val jsonDecoder = decoder as? JsonDecoder
            ?: throw SerializationException("Trigger 는 JSON 디코더에서만 쓸 수 있다")
        // ⚠️ `.jsonObject` 를 쓰지 않는다 — 객체가 아니면 IllegalArgumentException 이 SerializationException 을
        //    잡는 자리를 지나 문서에 없는 예외로 샌다(감사 SF-A4).
        val obj = jsonDecoder.decodeJsonElement() as? JsonObject
            ?: throw SerializationException("Trigger 가 객체가 아니다")

        val triggerId = (obj["trigger_id"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: throw SerializationException("Trigger.trigger_id 가 없거나 문자열이 아니다")
        val type = (obj["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: throw SerializationException("Trigger.type 이 없거나 문자열이 아니다")
        val payload = obj["payload"]?.let { parseLenientStringMap(it) }

        return Trigger(triggerId = triggerId, type = type, payload = payload)
    }

    override fun serialize(encoder: Encoder, value: Trigger) {
        val jsonEncoder = encoder as? JsonEncoder
            ?: throw SerializationException("Trigger 는 JSON 인코더에서만 쓸 수 있다")
        val json = buildJsonObject {
            put("trigger_id", value.triggerId)
            put("type", value.type)
            value.payload?.let { map ->
                put("payload", buildJsonObject { map.forEach { (k, v) -> put(k, v) } })
            }
        }
        jsonEncoder.encodeJsonElement(json)
    }
}

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
    @SerialName("profile_id") val profileId: String,
    @SerialName("device_model") val deviceModel: String? = null,
    @SerialName("os_name") val osName: String? = null,
    @SerialName("os_version") val osVersion: String? = null,
    @SerialName("app_version") val appVersion: String? = null,
    @SerialName("sdk_version") val sdkVersion: String? = null,
    @SerialName("device_language") val deviceLanguage: String? = null,
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
    @SerialName("profile_id") val profileId: String,
    @SerialName("visitor_id") val visitorId: String,
    @SerialName("floor_id") val floorId: String,
    @SerialName("zone_id") val zoneId: String,
    val status: String,
    @SerialName("occurred_at") val occurredAt: String,
    @SerialName("platform_name") val platformName: String,
)

/** /positioning/logs 의 points[] 요소. */
@Serializable
internal data class PositionPoint(
    @SerialName("floor_id") val floorId: String,
    @Serializable(with = CoordinatesSerializer::class) val coordinates: Coordinates,
    @SerialName("captured_at") val capturedAt: String,
)

/** POST /positioning/logs — 좌표 벌크(봉투 1회 + points[] 반복, 요청당 ≤500). */
@Serializable
internal data class ReqPositionBulk(
    @SerialName("profile_id") val profileId: String,
    @SerialName("visitor_id") val visitorId: String,
    @SerialName("platform_name") val platformName: String,
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
    @SerialName("profile_id") val profileId: String,
    @SerialName("platform_name") val platformName: String,
    @SerialName("sdk_version") val sdkVersion: String,
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
        // ⚠️ 200 에 null·[] 이 와도 E5005(Decoding)로 끝나야 한다 — `.jsonObject` 는 IllegalArgumentException(SF-A4).
        val obj = jsonDecoder.decodeJsonElement() as? JsonObject
            ?: throw SerializationException("ResVerify 가 객체가 아니다")

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
    @SerialName("tenant_code") val tenantCode: String? = null,
    @SerialName("google_map_key") val googleMapKey: String? = null,
    @SerialName("geo_sdk_key") val geoSdkKey: String? = null,
    @SerialName("geo_partner_key") val geoPartnerKey: String? = null,
    @SerialName("geo_base_url") val geoBaseUrl: String? = null,
)

/** GET /positioning/buildings 응답의 층 항목. */
@Serializable
internal data class FloorRef(
    @SerialName("floor_id") val floorId: String,
    val name: String,
)

/** GET /positioning/buildings 응답의 건물 항목. */
@Serializable
internal data class BuildingRef(
    @SerialName("building_id") val buildingId: String,
    val name: String,
    @SerialName("store_id") val storeId: Int? = null,
    /** 서버가 생략 가능 → provider 하드코딩 floorId 폴백. */
    val floors: List<FloorRef>? = null,
)

/** GET /positioning/buildings 응답. */
@Serializable
internal data class ResBuildings(
    @SerialName("synced_at") val syncedAt: String,
    val buildings: List<BuildingRef>,
)

/** GET /positioning/floors/{floor_id} 응답의 존 판정 파라미터 9종. */
@Serializable
internal data class ZoneMeta(
    @SerialName("zone_id") val zoneId: String,
    val name: String,
    /** 미터 좌표 다각형. */
    val polygon: List<List<Double>>? = null,
    @SerialName("trigger_type") val triggerType: String,
    @SerialName("dwell_seconds") val dwellSeconds: Int? = null,
    @SerialName("in_dist") val inDist: Double,
    @SerialName("in_count") val inCount: Int,
    @SerialName("in_count_interval") val inCountInterval: Int,
    @SerialName("out_period") val outPeriod: Int,
    val priority: Int,
    @SerialName("call_inout") val callInout: Boolean,
    @SerialName("is_active") val isActive: Boolean,
)

/** GET /positioning/floors/{floor_id} 응답. */
@Serializable
internal data class ResFloorConfig(
    @SerialName("floor_id") val floorId: String,
    @SerialName("building_id") val buildingId: String? = null,
    val name: String,
    @SerialName("synced_at") val syncedAt: String,
    val zones: List<ZoneMeta>,
    /** 현재 항상 빈 리스트(공간 서비스 앵커 API 대기). */
    val anchors: List<String>,
)

/** POST /events/zone 응답. 시책은 하나씩 읽는다 — 하나가 깨져도 나머지 시책은 앱에 간다(SF-A5). */
@Serializable
internal data class ResZoneEvent(
    val accepted: Boolean,
    @SerialName("event_id") val eventId: String,
    @Serializable(with = LenientTriggerListSerializer::class) val triggers: List<Trigger>,
)

/** [ResZoneEvent.triggers] — 요소 단위로 관대하게. */
internal object LenientTriggerListSerializer : LenientListSerializer<Trigger>(TriggerSerializer)

/** POST /positioning/logs 응답. */
@Serializable
internal data class ResPositionBulk(@SerialName("accepted_count") val acceptedCount: Int)

// MARK: - 프로필 (서버 TBD — SDK 가 계약을 정의한다)

/** POST /profiles 응답 — 서버가 profileId 를 발급한다. */
@Serializable
internal data class ResProfileCreate(@SerialName("profile_id") val profileId: String)

/** GET·PUT /profiles/{id} 응답. */
@Serializable
internal data class ResProfile(
    @SerialName("profile_id") val profileId: String,
    val attributes: Map<String, String>? = null,
)

/** DELETE /profiles/{id} 응답. */
@Serializable
internal data class ResProfileDelete(val deleted: Boolean)

/** POST /logs 응답. */
@Serializable
internal data class ResSdkLogs(@SerialName("accepted_count") val acceptedCount: Int)
