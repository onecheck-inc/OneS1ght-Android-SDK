package co.onecheck.ones1ght.android.model

import co.onecheck.ones1ght.android.internal.SdkJson
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 사양서 §6/§7의 JSON 예시가 DTO 로 1:1 매핑되는지 검증. 포팅 원본: DTOsTests.swift.
 */
class DtosTest {

    // MARK: 응답 디코딩 — 사양서 예시 그대로

    @Test fun decodeResVerify() {
        val json = """{ "valid": true, "tenant_code": "onecheck-internal", "positioning_enabled": true }"""
        val res = SdkJson.decodeFromString<ResVerify>(json)
        assertTrue(res.valid)
        assertEquals("onecheck-internal", res.tenantCode)
        assertTrue(res.positioningEnabled)
    }

    @Test fun decodeResBuildings() {
        val json = """
            { "synced_at": "2026-07-16T09:00:00Z",
              "buildings": [
                { "building_id": "b-uuid", "name": "금정역 skv1", "store_id": 3,
                  "floors": [ { "floor_id": "f-uuid", "name": "f-uuid" } ] } ] }
        """.trimIndent()
        val res = SdkJson.decodeFromString<ResBuildings>(json)
        assertEquals("금정역 skv1", res.buildings.first().name)
        assertEquals("f-uuid", res.buildings.first().floors?.first()?.floorId)
    }

    @Test fun decodeResZoneEventWithTriggers() {
        val json = """
            { "accepted": true, "event_id": "evt_a1b2c3",
              "triggers": [ { "trigger_id": "act_9f3", "type": "coupon",
                              "payload": { "title": "아메리카노 무료" } } ] }
        """.trimIndent()
        val res = SdkJson.decodeFromString<ResZoneEvent>(json)
        assertEquals("coupon", res.triggers.first().type)
        assertEquals("아메리카노 무료", res.triggers.first().payload?.get("title"))
    }

    @Test fun decodeResPositionBulk() {
        val res = SdkJson.decodeFromString<ResPositionBulk>("""{ "accepted_count": 100 }""")
        assertEquals(100, res.acceptedCount)
    }

    // MARK: 요청 인코딩 — snake_case 키·상태 원문 확인

    @Test fun encodeReqZoneEventProducesSnakeCaseAndRawStatus() {
        val req = ReqZoneEvent(
            profileId = "A",
            visitorId = "v-20260718-001",
            floorId = "F",
            zoneId = "Z",
            status = "DWELL",
            occurredAt = "2026-07-18T08:00:00Z",
            platformName = "Android",
        )
        val obj = SdkJson.parseToJsonElement(SdkJson.encodeToString(req)).jsonObject
        assertEquals("DWELL", obj["status"]?.jsonPrimitive?.content)
        assertEquals("2026-07-18T08:00:00Z", obj["occurred_at"]?.jsonPrimitive?.content)
        assertEquals("A", obj["profile_id"]?.jsonPrimitive?.content)
    }

    @Test fun encodeReqVerifyOmitsNilFields() {
        val req = ReqVerify(
            platformName = "Android",
            appId = null,
            client = ClientInfo(
                profileId = "A",
                deviceModel = null,
                osName = null,
                osVersion = null,
                appVersion = null,
                sdkVersion = null,
                deviceLanguage = null,
                attributes = null,
            ),
        )
        val obj = SdkJson.parseToJsonElement(SdkJson.encodeToString(req)).jsonObject
        val client = obj["client"]?.jsonObject
        assertEquals(1, client?.size)
        assertNull(obj["app_id"])
    }

    // MARK: 브리핑에 명시된 필수 케이스

    @Test fun zoneEventEncodesExactWireJson() {
        val s = SdkJson.encodeToString(
            ReqZoneEvent("p1", "v-20260820-001", "14", "z9", "DWELL", "2026-07-18T08:00:00.123Z", "Android"),
        )
        assertEquals(
            """{"profile_id":"p1","visitor_id":"v-20260820-001","floor_id":"14","zone_id":"z9","status":"DWELL","occurred_at":"2026-07-18T08:00:00.123Z","platform_name":"Android"}""",
            s,
        )
    }

    @Test fun nullFieldsAreOmitted() {
        assertEquals("""{"platform_name":"Android"}""", SdkJson.encodeToString(ReqVerify("Android")))
    }

    @Test fun verifyToleratesMixedRemoteConfig() {
        val r = SdkJson.decodeFromString<ResVerify>(
            """{"valid":true,"positioning_enabled":true,"tenant_code":5,"remote_config":{"a":true,"b":3,"c":1.5,"d":"x","e":{"n":1},"f":[1]}}""",
        )
        assertNull(r.tenantCode)
        assertEquals(mapOf("a" to "true", "b" to "3", "c" to "1.5", "d" to "x"), r.remoteConfig)
    }

    @Test fun remoteConfigNotObjectBecomesEmpty() {
        val r = SdkJson.decodeFromString<ResVerify>(
            """{"valid":true,"positioning_enabled":true,"remote_config":"oops"}""",
        )
        assertEquals(emptyMap<String, String>(), r.remoteConfig)
    }

    @Test fun triggerPayloadLenient() {
        val json = """
            { "accepted": true, "event_id": "evt_1",
              "triggers": [ { "trigger_id": "t1", "type": "generic",
                              "payload": {"k":1,"n":null,"o":{}} } ] }
        """.trimIndent()
        val res = SdkJson.decodeFromString<ResZoneEvent>(json)
        assertEquals(1, res.triggers.size)
        assertEquals(mapOf("k" to "1"), res.triggers.first().payload)
    }

    @Test fun triggerPayloadExplicitNullBecomesEmptyMap() {
        // iOS LenientStringMap 은 절대 throw 하지 않는다 — 객체가 아니면(null 포함) 빈 맵.
        val json = """
            { "accepted": true, "event_id": "evt_2",
              "triggers": [ { "trigger_id": "t2", "type": "generic", "payload": null } ] }
        """.trimIndent()
        val res = SdkJson.decodeFromString<ResZoneEvent>(json)
        assertEquals(emptyMap<String, String>(), res.triggers.first().payload)
    }

    @Test fun triggerPayloadMissingKeyStaysNull() {
        val json = """
            { "accepted": true, "event_id": "evt_3",
              "triggers": [ { "trigger_id": "t3", "type": "generic" } ] }
        """.trimIndent()
        val res = SdkJson.decodeFromString<ResZoneEvent>(json)
        assertNull(res.triggers.first().payload)
    }

    @Test fun resVerifyEncodeDecodeRoundTrip() {
        val original = ResVerify(
            valid = true,
            positioningEnabled = true,
            tenantCode = "onecheck-internal",
            positionRateHz = 4,
            remoteConfig = mapOf("env" to "prod"),
        )
        val encoded = SdkJson.encodeToString(original)
        assertEquals(
            """{"valid":true,"positioning_enabled":true,"tenant_code":"onecheck-internal","position_rate_hz":4,"remote_config":{"env":"prod"}}""",
            encoded,
        )
        val decoded = SdkJson.decodeFromString<ResVerify>(encoded)
        assertEquals(original, decoded)
    }

    @Test fun iso8601HasMillisAndZ() {
        assertEquals("1970-01-01T00:00:00.123Z", co.onecheck.ones1ght.android.internal.Iso8601.format(123))
    }
}
