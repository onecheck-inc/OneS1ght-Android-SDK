package co.onecheck.ones1ght.android.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * ⚠️ 서버가 항목을 늘렸을 때 구버전 SDK 가 깨지면 안 된다 —
 * 모르는 event 타입도, 모르는 data 필드도 조용히 넘어가야 한다.
 *
 * 포팅 원본: ConfigChangeTests.swift.
 */
class ConfigChangeParseTest {

    @Test
    fun zonesChangedCarriesFloorAndSeq() {
        val (seq, change) = ConfigChange.parse(
            "zones.changed",
            """{"seq":42,"tenant_id":7,"floor_id":"f-1"}""",
        )!!

        assertEquals(42, seq)
        assertEquals(ConfigChange.ZonesChanged(floorId = "f-1"), change)
    }

    @Test
    fun helloCarriesSeqButNoChange() {
        val (seq, change) = ConfigChange.parse("hello", """{"seq":100,"tenant_id":7}""")!!

        assertEquals(100, seq)
        assertNull(change)
    }

    @Test
    fun unknownEventTypeYieldsSeqOnlyNotNil() {
        // 서버가 새 타입을 늘려도 seq 추적은 계속돼야 한다 — 안 그러면 갭 오탐이 난다.
        val (seq, change) = ConfigChange.parse("something.new", """{"seq":43,"tenant_id":7}""")!!

        assertEquals(43, seq)
        assertNull(change)
    }

    @Test
    fun unknownDataFieldsAreIgnored() {
        val (_, change) = ConfigChange.parse(
            "zones.changed",
            """{"seq":1,"tenant_id":7,"floor_id":"f","brand_new":{"a":[1,2]}}""",
        )!!

        assertEquals(ConfigChange.ZonesChanged(floorId = "f"), change)
    }

    @Test
    fun malformedJsonIsDroppedWithoutCrashing() {
        assertNull(ConfigChange.parse("zones.changed", "not json"))
    }

    @Test
    fun sdkConfigChangedReadsSnakeCaseFields() {
        val (_, change) = ConfigChange.parse(
            "sdk.config.changed",
            """{"seq":5,"tenant_id":7,"rate_hz":4,"log_level":"info"}""",
        )!!

        assertEquals(ConfigChange.SdkConfigChanged(rateHz = 4, logLevel = "info"), change)
    }

    // 아래는 iOS 원본엔 없지만, RulesChanged 의 zoneId 숫자→문자열 변환(zone_id 는 서버에서
    // 숫자로 온다)과 planChanged/데이터 없는 프레임 경로를 직접 커버하려고 추가했다
    // (LiveConfigStreamIngestTest 가 이 변환을 실제 prod 페이로드로 통합 검증한다).

    @Test
    fun rulesChangedConvertsNumericZoneIdToString() {
        val (seq, change) = ConfigChange.parse(
            "rules.changed",
            """{"seq":38,"tenant_id":1,"zone_id":264,"rule_id":120,"status":"active"}""",
        )!!

        assertEquals(38, seq)
        assertEquals(ConfigChange.RulesChanged(zoneId = "264"), change)
    }

    @Test
    fun rulesChangedWithoutZoneIdYieldsNullZoneId() {
        val (_, change) = ConfigChange.parse("rules.changed", """{"seq":1,"tenant_id":7}""")!!

        assertEquals(ConfigChange.RulesChanged(zoneId = null), change)
    }

    @Test
    fun planChangedCarriesFloorId() {
        val (_, change) = ConfigChange.parse(
            "plan.changed",
            """{"seq":9,"tenant_id":7,"floor_id":"f-2"}""",
        )!!

        assertEquals(ConfigChange.PlanChanged(floorId = "f-2"), change)
    }

    @Test
    fun emptyObjectYieldsNullSeqAndNullChangeForKnownEvent() {
        val (seq, change) = ConfigChange.parse("zones.changed", "{}")!!

        assertNull(seq)
        assertEquals(ConfigChange.ZonesChanged(floorId = null), change)
    }
}
