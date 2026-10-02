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
import co.onecheck.ones1ght.android.internal.SdkJson
import co.onecheck.ones1ght.android.internal.decodeLenientList
import co.onecheck.ones1ght.android.internal.parseLenientStringMap
import co.onecheck.ones1ght.android.runtime.PositionRate
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
import kotlinx.serialization.json.JsonArray
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
    /** 액션 ID(와이어 이름 trigger_id). 서버가 숫자로 주어도 문자열로 받는다. 서버가 빼면 빈 문자열(iOS #55 와 같다). */
    public val triggerId: String,
    /**
     * 액션 종류 — `signage` · `coupon` · `tracking` · `merch` · `generic`. ⚠️ 문자열이다 — 서버가 종류를 늘릴 수 있어
     * 모르는 값도 그대로 온다. 서버가 빼면 `generic`(iOS #55 와 같다).
     */
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

        // ⚠️ 관대하게 읽는다(iOS S17). id 가 숫자로 와도, 빠져도 트리거는 산다 — 쿠폰을 그리는 데 필요한 것은
        //    payload 다. 예전엔 둘 중 하나만 빠져도 그 트리거가 통째로 버려졌다.
        val triggerId = lenientId(obj["trigger_id"]) ?: ""
        val type = (obj["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: GENERIC_TRIGGER_TYPE
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

/**
 * 서버가 값을 주지 않을 때 쓰는 기본값 — 종전 SDK 하드코딩과 같아 동작이 바뀌지 않는다. **SDK 내부 전용이다**
 * (감사 SF-C1 · iOS K4 — 0.0.6 까지 공개였다).
 */
internal object SdkDefaults {
    const val POSITION_RATE_HZ: Int = PositionRate.DEFAULT_HZ
    const val MIN_RATE_HZ: Int = PositionRate.MIN_HZ
    const val MAX_RATE_HZ: Int = PositionRate.MAX_HZ
}

// MARK: - 요청 (SDK → 서버)

/**
 * POST /auth/verify — 키 검증 + 클라 등록(초기화 1회).
 *
 * 서버 계약의 `client` 블록(기기 정보)은 보내지 않는다 — 늘 null 이라 생략되던 죽은 필드였다(감사 SF-C2 · iOS K8).
 */
@Serializable
internal data class ReqVerify(
    @SerialName("platform_name") val platformName: String,
    @SerialName("app_id") val appId: String? = null,
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
 *
 * ⚠️ `geo_partner_key`(쓰기 권한 파트너 키)는 **읽지 않는다** — SDK 는 쓸 데가 없고, 들고 있으면 기기에서
 *    꺼낼 수 있다(감사 SF-A10). 서버가 아직 내려줘도 `ignoreUnknownKeys` 로 버려진다.
 */
@Serializable
internal data class ResSdkConfig(
    @SerialName("tenant_code") val tenantCode: String? = null,
    @SerialName("google_map_key") val googleMapKey: String? = null,
    @SerialName("geo_sdk_key") val geoSdkKey: String? = null,
    @SerialName("geo_base_url") val geoBaseUrl: String? = null,
)

/**
 * POST /events/zone 응답. 시책은 하나씩 읽는다 — 하나가 깨져도 나머지 시책은 앱에 간다(SF-A5).
 *
 * ⚠️ 원소 단위로 관대하게 읽는다(iOS S17). 예전엔 event_id 가 숫자·null 이거나 accepted 가 빠지면 응답 전체가 디코드
 *    실패 → 그 존 이벤트의 쿠폰이 **전부** 조용히 사라졌다. 200 이면 받은 것이다(accepted 기본 true).
 */
@Serializable(with = ResZoneEventSerializer::class)
internal data class ResZoneEvent(
    val accepted: Boolean = true,
    val eventId: String? = null,
    val triggers: List<Trigger> = emptyList(),
)

/** [ResZoneEvent] — 필드마다 따로 시도한다. 객체가 아니면(null·배열) 그건 응답 전체가 틀린 것이라 던진다. */
internal object ResZoneEventSerializer : KSerializer<ResZoneEvent> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("ResZoneEvent") {
        element<Boolean>("accepted", isOptional = true)
        element<String>("event_id", isOptional = true)
        element("triggers", LenientTriggerListSerializer.descriptor, isOptional = true)
    }

    override fun deserialize(decoder: Decoder): ResZoneEvent {
        val jsonDecoder = decoder as? JsonDecoder
            ?: throw SerializationException("ResZoneEvent 는 JSON 디코더에서만 쓸 수 있다")
        val obj = jsonDecoder.decodeJsonElement() as? JsonObject
            ?: throw SerializationException("ResZoneEvent 가 객체가 아니다")
        return ResZoneEvent(
            accepted = (obj["accepted"] as? JsonPrimitive)?.booleanOrNull ?: true,
            eventId = lenientId(obj["event_id"]),
            triggers = (obj["triggers"] as? JsonArray)?.let { decodeLenientList(it, TriggerSerializer) } ?: emptyList(),
        )
    }

    override fun serialize(encoder: Encoder, value: ResZoneEvent) {
        val jsonEncoder = encoder as? JsonEncoder
            ?: throw SerializationException("ResZoneEvent 는 JSON 인코더에서만 쓸 수 있다")
        jsonEncoder.encodeJsonElement(
            buildJsonObject {
                put("accepted", value.accepted)
                value.eventId?.let { put("event_id", it) }
                put("triggers", SdkJson.encodeToJsonElement(LenientTriggerListSerializer, value.triggers))
            },
        )
    }
}

/** 트리거 type 이 빠졌을 때 — iOS 와 같은 값. */
private const val GENERIC_TRIGGER_TYPE: String = "generic"

/** id 자리 — 문자열·정수 어느 쪽이든 문자열로. 없거나 null 이거나 다른 타입이면 null(iOS `lenientID`). */
@JvmSynthetic // 최상위 internal 함수는 이름이 망글링되지 않아 Java 에 보인다
internal fun lenientId(element: kotlinx.serialization.json.JsonElement?): String? {
    val p = element as? JsonPrimitive ?: return null
    if (p is kotlinx.serialization.json.JsonNull) return null
    if (p.isString) return p.content
    return p.content.toLongOrNull()?.toString()
}

/** [ResZoneEvent.triggers] — 요소 단위로 관대하게. */
internal object LenientTriggerListSerializer : LenientListSerializer<Trigger>(TriggerSerializer)

/** POST /positioning/logs 응답. */
@Serializable
internal data class ResPositionBulk(@SerialName("accepted_count") val acceptedCount: Int? = null) // 관대(iOS S17)

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
