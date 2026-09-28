package co.onecheck.ones1ght.android.internal

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [LenientStringMapSerializer] — 값 종류가 섞인 JSON 객체를 [Map]<String, String>으로
 * 접어 읽는지 검증한다. 포팅 원본: DTOs.swift 의 `LenientStringMap`.
 */
class LenientStringMapTest {

    @Test fun mixedValueTypesCoerceToString() {
        val map = Json.decodeFromString(
            LenientStringMapSerializer,
            """{"a":true,"b":3,"c":1.5,"d":"x","e":{"n":1},"f":[1]}""",
        )
        assertEquals(mapOf("a" to "true", "b" to "3", "c" to "1.5", "d" to "x"), map)
    }

    @Test fun nullEntryIsSkipped() {
        val map = Json.decodeFromString(LenientStringMapSerializer, """{"k":1,"n":null,"o":{}}""")
        assertEquals(mapOf("k" to "1"), map)
    }

    @Test fun nonObjectBecomesEmptyMap() {
        val map = Json.decodeFromString(LenientStringMapSerializer, "\"oops\"")
        assertEquals(emptyMap<String, String>(), map)
    }

    @Test fun emptyObjectBecomesEmptyMap() {
        val map = Json.decodeFromString(LenientStringMapSerializer, "{}")
        assertEquals(emptyMap<String, String>(), map)
    }
}
