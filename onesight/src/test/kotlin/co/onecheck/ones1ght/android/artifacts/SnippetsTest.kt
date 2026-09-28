package co.onecheck.ones1ght.android.artifacts

import co.onecheck.ones1ght.android.OneS1ght
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Snippets/android.json 이 실제 SDK 와 어긋나지 않는지 지킨다. 포팅 원본: SnippetsTests.swift.
 *
 * 이 파일이 있는 이유는 하나다 — 스니펫은 **고객사 코드에 그대로 들어간다.** 코딩
 * 에이전트(MCP)가 받아 그들의 프로젝트에 붙이므로, 사람이 읽고 옮겨 적을 때처럼
 * "어? 이건 아닌데" 하고 걸러 주는 단계가 없다. 한 글자 틀리면 그대로 심긴다.
 *
 * ⚠️ 에러 코드 존재 검사([referencedErrorCodesExist])는 `SdkErrorCode`(병행 작업 중이라 이
 * 태스크에서는 참조하지 않는다)를 쓰지 않고, 사양서 §7 의 26개 E-코드를 하드코딩한
 * [KNOWN_ERROR_CODES] 와 대조한다. `SdkErrorCode` enum 이 만들어지면
 * `SdkErrorCode.entries.map { it.code }` 로 교체할 것.
 */
class SnippetsTest {

    companion object {
        /** 레포 루트 — 테스트 working dir 은 모듈 디렉터리(onesight/)라 한 단계 올라간다. */
        private val repoRoot = File(System.getProperty("user.dir")!!).parentFile
        private val snippetFile = File(repoRoot, "Snippets/android.json")

        // 사양서 §7 — SdkErrorCode 26개(E1005·E1006·E3005 는 폐기돼 재사용하지 않는다).
        // SdkErrorCode enum 이 생기면 그 값으로 교체할 것.
        private val KNOWN_ERROR_CODES = setOf(
            "E1001", "E1002", "E1003", "E1004", "E1007",
            "E2001", "E2002", "E2003",
            "E3001", "E3002", "E3003", "E3004", "E3006", "E3007", "E3008", "E3009",
            "E4001", "E4002", "E4003", "E4004",
            "E5001", "E5002", "E5003", "E5004", "E5005", "E5006",
        )

        // Global Constraints 의 공급사 이름 금지어.
        private val VENDOR_NAMES = listOf(
            "Geoplan", "geoplan", "gpa-", "gpi-", "GeoSpace", "Geospace", "ihub",
        )

        // iOS 재설계에서 사라진 API 이름 — 포팅 중 실수로 남기 쉽다.
        private val RETIRED_API_NAMES = listOf(
            "OneS1ghtSDK", "loadFloor", "geospaceKey", "positioningAvailability",
            "anonUserId", "onZoneEvent", "onesight-sdk",
            // 고객은 SDK 키 하나만 넣는다 — 나머지는 콘솔이 정본이라 인자가 없다.
            "geoSdkKey", "geoPartnerKey", "geoBaseUrl",
        )
    }

    private val json: JsonObject by lazy {
        Json.parseToJsonElement(snippetFile.readText()).jsonObject
    }

    private val steps: List<JsonObject> by lazy {
        json["steps"]?.jsonArray?.map { it.jsonObject } ?: emptyList()
    }

    /** 스니펫 전체를 하나의 문자열로 — 메서드 이름이 어디에 있든 잡는다. */
    private val allCode: String by lazy {
        buildString {
            for (step in steps) {
                step["code"]?.jsonPrimitive?.contentOrNull?.let { append(it).append('\n') }
                step["files"]?.jsonArray?.forEach { f ->
                    f.jsonObject["code"]?.jsonPrimitive?.contentOrNull?.let { append(it).append('\n') }
                }
            }
        }
    }

    @Test fun fileParses() {
        assertEquals("android", json["platform"]?.jsonPrimitive?.contentOrNull)
        assertTrue(steps.isNotEmpty())
    }

    /** ⚠️ 핵심 — 버전을 올리면서 스니펫을 안 고치면 여기서 걸린다. */
    @Test fun declaredVersionMatchesTheSdk() {
        assertEquals(
            "Snippets/android.json 의 sdkVersion 이 OneS1ght.SDK_VERSION 과 다르다 — 판올림 때 함께 고칠 것",
            OneS1ght.SDK_VERSION,
            json["sdkVersion"]?.jsonPrimitive?.contentOrNull,
        )
    }

    /** 스니펫이 참조하는 에러 코드가 실제로 존재하는가. */
    @Test fun referencedErrorCodesExist() {
        for (step in steps) {
            val stepId = step["id"]?.jsonPrimitive?.contentOrNull ?: "?"
            for (codeEl in step["throws"]?.jsonArray ?: continue) {
                val code = codeEl.jsonPrimitive.content
                assertTrue("$stepId 가 존재하지 않는 코드를 참조한다: $code", KNOWN_ERROR_CODES.contains(code))
            }
        }
    }

    /** 사라진 API 이름이 스니펫에 남아 있지 않은가. */
    @Test fun noRetiredApiNames() {
        val code = allCode
        for (retired in RETIRED_API_NAMES) {
            assertFalse("스니펫에 사라진 이름이 남아 있다: $retired", code.contains(retired))
        }
    }

    /** 고객이 읽는 코드에 공급사 이름이 나오면 안 된다 — 우리는 OneS1ght 로 판다. */
    @Test fun noVendorNamesInSnippets() {
        val text = snippetFile.readText()
        for (vendor in VENDOR_NAMES) {
            assertFalse("스니펫에 공급사 이름이 있다: $vendor", text.contains(vendor))
        }
    }

    /**
     * ⚠️ 컴파일되지 않는 것으로 알려진 패턴이 스니펫에 들어가면 안 된다.
     *
     * iOS 스니펫에는 `savedProfileId ?? (try await ...)` 가 실제로 들어 있었고, `??` 오른쪽이
     * autoclosure 라 컴파일되지 않았다. 코틀린의 `?:` 는 같은 제약이 없지만, iOS 소스를
     * 참고해 코틀린 스니펫을 옮기다 그 조각이 그대로 복사되는 사고를 여기서 막는다.
     */
    @Test fun noUncompilablePatterns() {
        // 주석은 걸러낸다 — 함정을 설명하는 주석에 그 패턴이 그대로 나올 수 있다.
        val code = allCode
            .lineSequence()
            .joinToString("\n") { line -> line.substringBefore("//") }
        val patterns = listOf(
            "?? (try" to "Swift autoclosure 함정 — 코틀린 스니펫에 남아 있으면 안 된다",
            "?? (await" to "Swift autoclosure 함정 — 코틀린 스니펫에 남아 있으면 안 된다",
            "?? try" to "Swift autoclosure 함정 — 코틀린 스니펫에 남아 있으면 안 된다",
        )
        for ((pattern, why) in patterns) {
            assertFalse("스니펫에 컴파일되지 않는 패턴이 있다: $pattern — $why", code.contains(pattern))
        }
    }

    /** 연동에 반드시 필요한 단계가 빠지지 않았는가. */
    @Test fun requiredStepsArePresent() {
        val ids = steps.mapNotNull { it["id"]?.jsonPrimitive?.contentOrNull }
        for (required in listOf("install", "permission", "initialize", "profile", "selectFloor", "begin", "end")) {
            assertTrue("필수 단계 누락: $required", ids.contains(required))
        }
    }

    /** order 가 중복되거나 비면 에이전트가 순서를 못 잡는다. */
    @Test fun stepOrderIsUniqueAndComplete() {
        val orders = steps.mapNotNull { it["order"]?.jsonPrimitive?.intOrNull }
        assertEquals("order 가 없는 단계가 있다", steps.size, orders.size)
        assertEquals("order 가 중복된다", orders.toSet().size, orders.size)
    }

    /** 스니펫이 부르는 공개 메서드가 실제 표면에 있는가 — 이름을 문자열로 대조한다. */
    @Test fun coreApiNamesAppear() {
        val code = allCode
        for (expected in listOf(
            "OneS1ght.initialize(", "createProfile(", "identify(",
            "buildings(", "floors(", "setFloorMap(",
            "floorSession(", ".begin(", "OneS1ght.deviceAvailability",
        )) {
            assertTrue("핵심 API 가 스니펫에 없다: $expected", code.contains(expected))
        }
    }

    /** 안내 문구가 실제 요구사항과 맞는가 — 옛 값이 남지 않게. */
    @Test fun requirementsAreCurrent() {
        val req = json["requirements"]?.jsonObject
        val build = req?.get("build")?.jsonObject
        assertEquals("37", build?.get("compileSdk")?.jsonPrimitive?.contentOrNull)

        val positioning = req?.get("positioning")?.jsonObject
        assertEquals("Android 17 (API 37)+", positioning?.get("os")?.jsonPrimitive?.contentOrNull)
    }
}
