package co.onecheck.ones1ght.android.artifacts

import co.onecheck.ones1ght.android.OneS1ght
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Migrations/android.json 이 실제 판올림을 따라오는지 지킨다. 포팅 원본: MigrationsTests.swift.
 *
 * 마이그레이션 지침이 없으면 MCP 는 "변경 없음" 이라고 답할 근거가 없고, 에이전트는
 * 추측으로 고객사 코드를 고친다. 그래서 파일이 낡는 것을 빌드에서 잡는다.
 *
 * ⚠️ 0.0.1 은 이 SDK 의 첫 판이라 `migrations` 가 빈 배열이다 — iOS MigrationsTests 는
 * 최소 한 칸(경로 종점)을 요구했지만, 그건 iOS 가 이미 0.1.0 을 지나온 뒤였기 때문이다.
 * 여기서는 0.0.1 자체가 첫 판이므로 [latestMigrationLandsOnCurrentVersionOrChainIsEmpty]
 * 가 그 경우만 건너뛴다 — 그 외 버전에서 체인이 비면 여전히 실패해야 한다.
 */
class MigrationsTest {

    companion object {
        private val repoRoot = File(System.getProperty("user.dir")!!).parentFile
        private val migrationsFile = File(repoRoot, "Migrations/android.json")
    }

    private val json: JsonObject by lazy {
        Json.parseToJsonElement(migrationsFile.readText()).jsonObject
    }

    private val migrations: List<JsonObject> by lazy {
        json["migrations"]?.jsonArray?.map { it.jsonObject } ?: emptyList()
    }

    @Test fun fileParses() {
        assertEquals("android", json["platform"]?.jsonPrimitive?.contentOrNull)
    }

    /** ⚠️ 판올림하면서 이 파일을 안 고치면 여기서 걸린다. */
    @Test fun currentVersionMatchesTheSdk() {
        assertEquals(
            "Migrations/android.json 의 currentVersion 이 OneS1ght.SDK_VERSION 과 다르다 — 판올림 때 칸을 더할 것",
            OneS1ght.SDK_VERSION,
            json["currentVersion"]?.jsonPrimitive?.contentOrNull,
        )
    }

    /**
     * 마지막 칸의 to 가 현재 버전이어야 한다 — 아니면 최신으로 가는 길이 없다.
     * 0.0.1(첫 판, 이전 버전이 없다)에 한해 빈 체인을 허용한다.
     */
    @Test fun latestMigrationLandsOnCurrentVersionOrChainIsEmpty() {
        val last = migrations.lastOrNull()
        if (last == null) {
            assertEquals(
                "마이그레이션이 하나도 없다 — 0.0.1(첫 판) 이 아니면 첫 판올림부터 기록할 것",
                "0.0.1",
                OneS1ght.SDK_VERSION,
            )
            return
        }
        assertEquals(
            "마지막 마이그레이션의 to 가 현재 버전이 아니다",
            OneS1ght.SDK_VERSION,
            last["to"]?.jsonPrimitive?.contentOrNull,
        )
    }

    /** **경로 연속성** — 앞 칸의 to 가 다음 칸의 from 이어야 이어 밟을 수 있다. */
    @Test fun migrationChainHasNoGaps() {
        for (i in 1 until migrations.size) {
            val prevTo = migrations[i - 1]["to"]?.jsonPrimitive?.contentOrNull
            val from = migrations[i]["from"]?.jsonPrimitive?.contentOrNull
            assertEquals("경로가 끊겼다: $prevTo 다음이 $from 에서 시작한다", prevTo, from)
        }
    }

    /** 모든 칸에 사람이 읽을 요약과 할 일이 있어야 한다. */
    @Test fun everyMigrationExplainsItself() {
        for (step in migrations) {
            val from = step["from"]?.jsonPrimitive?.contentOrNull ?: "?"
            val to = step["to"]?.jsonPrimitive?.contentOrNull ?: "?"
            val label = "$from→$to"
            assertTrue(label, !step["summary"]?.jsonPrimitive?.contentOrNull.isNullOrEmpty())
            assertTrue(label, step["action"]?.jsonPrimitive?.contentOrNull != null)
            assertTrue("$label — breaking 을 명시할 것", step["breaking"]?.jsonPrimitive?.booleanOrNull != null)
        }
    }

    /**
     * ⚠️ breaking 이면 무엇을 고쳐야 하는지가 반드시 있어야 한다.
     * "깨지는 변경입니다" 만 알려주고 방법을 안 주면 에이전트가 추측하고, 그 추측이
     * 고객사 코드에 그대로 들어간다.
     */
    @Test fun breakingChangesCarryInstructions() {
        for (step in migrations) {
            if (step["breaking"]?.jsonPrimitive?.booleanOrNull != true) continue
            val from = step["from"]?.jsonPrimitive?.contentOrNull ?: "?"
            val to = step["to"]?.jsonPrimitive?.contentOrNull ?: "?"
            val changes = step["changes"]?.jsonArray ?: emptyList()
            assertFalse("$from→$to 는 breaking 인데 changes 가 비어 있다", changes.isEmpty())
            for (c in changes) {
                val obj = c.jsonObject
                assertTrue("변경 전 코드가 없다", obj.containsKey("before"))
                assertTrue("변경 후 코드가 없다", obj.containsKey("after"))
            }
        }
    }

    /** 같은 구간이 두 번 적히면 에이전트가 어느 쪽을 따를지 모른다. */
    /**
     * planned[] — 아직 나오지 않은 판의 계획(0.0.x 에서 @Deprecated 로 예고한 0.2 정리). 체인 밖이라 현재 버전보다 커야
     * 하고, 항목마다 무엇을(api)·어떻게 바뀌나(change)·코드를 어떻게 고치나(action)가 있어야 한다.
     */
    @Test fun plannedStepsAreAheadAndExplained() {
        val planned = json["planned"]?.jsonArray?.map { it.jsonObject } ?: emptyList()
        val current = OneS1ght.SDK_VERSION.split('.').map { it.toInt() }
        for (step in planned) {
            val version = step["version"]?.jsonPrimitive?.contentOrNull ?: error("planned 에 version 이 없다")
            val v = version.split('.').map { it.toInt() }
            assertTrue("planned $version 은 현재 ${OneS1ght.SDK_VERSION} 보다 커야 한다", compareValues(v[0] * 1_000_000 + v[1] * 1_000 + v[2], current[0] * 1_000_000 + current[1] * 1_000 + current[2]) > 0)
            assertTrue(migrations.none { it["to"]?.jsonPrimitive?.contentOrNull == version })
            val items = step["items"]?.jsonArray?.map { it.jsonObject } ?: emptyList()
            assertFalse("planned $version 에 항목이 없다", items.isEmpty())
            for (item in items) {
                for (k in listOf("api", "change", "action")) {
                    assertFalse("planned $version 항목에 $k 가 없다: $item", item[k]?.jsonPrimitive?.contentOrNull.isNullOrEmpty())
                }
            }
        }
    }

    @Test fun noDuplicateMigrationSteps() {
        val pairs = migrations.map {
            "${it["from"]?.jsonPrimitive?.contentOrNull}→${it["to"]?.jsonPrimitive?.contentOrNull}"
        }
        assertEquals("중복된 구간이 있다: $pairs", pairs.toSet().size, pairs.size)
    }
}
