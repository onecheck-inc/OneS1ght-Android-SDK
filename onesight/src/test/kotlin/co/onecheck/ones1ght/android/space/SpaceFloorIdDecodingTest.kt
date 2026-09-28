package co.onecheck.ones1ght.android.space

import co.onecheck.ones1ght.android.internal.SdkJson
import kotlinx.serialization.SerializationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 공간 서비스 건물 트리의 `floorId` 는 **숫자로 온다.**
 *
 * 2026-08-20 에 같은 원인으로 콘솔 GCH 화면이 전부 500 이 났고, SDK 에는 같은 결함이 남아
 * 있었다 — `floorId` 를 `String` 으로 선언해 두어 JSON 디코딩이 통째로 실패했다. 그 결과
 * 콘솔이 층을 0개로 주는 건물에서 폴백이 항상 죽어 층 드롭다운이 비었다.
 *
 * 아래 픽스처는 2026-08-24 prod `GET https://geospace.geoplan.io/api/m/buildings` 응답을
 * 그대로 옮긴 것이다. 포팅 원본: SpaceFloorIdDecodingTests.swift.
 */
class SpaceFloorIdDecodingTest {

    /** prod 실응답 — `floorId` 가 따옴표 없는 숫자다. */
    private val realResponse = """
        {
          "tenantId": "a4455e99-7b71-4ad1-98a7-654e4a851cec",
          "buildings": [
            {
              "buildingId": "0fe8f405-a710-44ee-96a2-f927c44b9cde",
              "buildingName": "금정역 skv1",
              "floors": [
                { "floorId": 14, "floorName": "607호", "hasPlan": true, "aligned": false }
              ]
            }
          ]
        }
    """.trimIndent()

    @Test fun decodesNumericFloorId() {
        val res = SdkJson.decodeFromString(SpaceBuildingsResponse.serializer(), realResponse)

        assertEquals(1, res.buildings.size)
        assertEquals("0fe8f405-a710-44ee-96a2-f927c44b9cde", res.buildings[0].buildingId)
        assertEquals(1, res.buildings[0].floors.size)
        // 숫자 14 가 문자열 "14" 로 정규화돼야 한다 — 콘솔 /floors 가 주는 형태와 같아야
        // 두 경로에서 온 층 ID 를 같은 값으로 다룰 수 있다.
        assertEquals("14", res.buildings[0].floors[0].floorId)
        assertEquals("607호", res.buildings[0].floors[0].floorName)
        assertTrue(res.buildings[0].floors[0].hasPlan)
    }

    /** 서버가 언젠가 문자열로 바꿔 보내도 깨지지 않아야 한다. */
    @Test fun stillDecodesStringFloorId() {
        val json = """
            {
              "buildings": [
                {
                  "buildingId": "b-1",
                  "buildingName": "이름",
                  "floors": [
                    { "floorId": "15", "floorName": "304로", "hasPlan": false }
                  ]
                }
              ]
            }
        """.trimIndent()

        val res = SdkJson.decodeFromString(SpaceBuildingsResponse.serializer(), json)

        assertEquals("15", res.buildings[0].floors[0].floorId)
        assertFalse(res.buildings[0].floors[0].hasPlan)
    }

    /**
     * 숫자도 문자열도 아니면 그 층만 버리는 게 아니라 디코딩이 실패해야 한다 — 조용히 빈
     * 목록을 돌려주면 이번과 같은 증상이 다시 숨는다.
     */
    @Test fun rejectsUnusableFloorId() {
        val json = """
            { "buildings": [ { "buildingId": "b", "buildingName": "n",
              "floors": [ { "floorId": null, "floorName": "x", "hasPlan": true } ] } ] }
        """.trimIndent()

        assertThrows(SerializationException::class.java) {
            SdkJson.decodeFromString(SpaceBuildingsResponse.serializer(), json)
        }
    }
}
