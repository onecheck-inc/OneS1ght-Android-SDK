package co.onecheck.ones1ght.android.runtime

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * 다국어 문구 조회 — 포팅 원본: SdkLocalized.swift + Resources/i18n/SdkLocalization.json.
 */
class SdkLocalizedTest {

    @Before
    @After
    fun resetLanguage() {
        SdkLocalized.language = null
        SdkLocalized.deviceLanguage = { "ko" }
    }

    /** `t("coord.stopFlush", 3)` — ko/en/ja 각각, `%d` 인자가 채워진다. */
    @Test fun tTranslatesKoEnJaWithFormatArg() {
        SdkLocalized.language = "ko"
        assertEquals("종료 — 잔여 3건 flush 시도", SdkLocalized.t("coord.stopFlush", 3))

        SdkLocalized.language = "en"
        assertEquals("Stopping — flushing 3 pending", SdkLocalized.t("coord.stopFlush", 3))

        SdkLocalized.language = "ja"
        assertEquals("終了 — 残り 3件を flush", SdkLocalized.t("coord.stopFlush", 3))
    }

    /** 언어를 지정하지 않으면 [SdkLocalized.deviceLanguage] 를 따른다. */
    @Test fun nullLanguageFallsBackToDeviceLanguage() {
        SdkLocalized.language = null
        SdkLocalized.deviceLanguage = { "ja" }
        assertEquals("終了 — 残り 3件を flush", SdkLocalized.t("coord.stopFlush", 3))
    }

    /** 테이블에 없는 언어면 ko 로 떨어진다 — 실제 JSON 에 없는 언어 코드를 직접 넣어 검증. */
    @Test fun unknownLanguageFallsBackToKo() {
        SdkLocalized.language = "fr"
        assertEquals("종료 — 잔여 3건 flush 시도", SdkLocalized.t("coord.stopFlush", 3))
    }

    /** 없는 키면 키 문자열 그대로 나온다. */
    @Test fun missingKeyReturnsKeyItself() {
        SdkLocalized.language = "ko"
        assertEquals("no.such.key", SdkLocalized.t("no.such.key"))
    }

    /** `%@` 인자가 치환되는지 확인한다. */
    @Test fun atSignArgumentIsSubstituted() {
        SdkLocalized.language = "en"
        assertEquals("verify passed (tenant: acme-corp)", SdkLocalized.t("coord.verifyPass", "acme-corp"))

        SdkLocalized.language = "ko"
        assertEquals("verify 통과 (tenant: acme-corp)", SdkLocalized.t("coord.verifyPass", "acme-corp"))
    }

    /** 패키징된 JSON 이 99 키 × 3언어(ko/ja/en) 인지 — 리소스 유실·언어 누락을 잡는다. */
    @Test fun jsonHasAllKeysTimesThreeLanguages() {
        val table = SdkLocalized.table
        // 0.0.6 109 + 엔진 재시도 2 − 죽은 층 설정 2(SF-A6) + 실시간 연결 5(S27) = 114
        // − 안 쓰는 키 49(감사 K8) + 코드 요약 34(code.*, 감사 SP-C8) = 99
        assertEquals(99, table.size)
        for ((key, entry) in table) {
            assertEquals("$key 의 언어 집합이 ko/ja/en 이 아니다", setOf("ko", "ja", "en"), entry.keys)
        }
        assertTrue(table.isNotEmpty())
    }

    /**
     * 키 × 3언어 전부를, 원문(Swift/C 스타일) 지정자에서 뽑아낸 타입에 맞는 더미
     * 인자로 실제 포맷해 본다 — `%lld`·`%012llX`·`%.1f`·`%@`·`%d` 조합이 하나라도
     * [java.util.Formatter] 를 못 넘기면(길이 수식어를 안 지웠다든지) 여기서 예외로 잡힌다.
     */
    @Test fun everyKeyFormatsInEveryLanguageWithoutThrowing() {
        val table = SdkLocalized.table
        for ((key, byLang) in table) {
            for (lang in listOf("ko", "ja", "en")) {
                val raw = byLang.getValue(lang)
                SdkLocalized.language = lang
                try {
                    SdkLocalized.t(key, *dummyArgsFor(raw).toTypedArray())
                } catch (e: Exception) {
                    fail("$key ($lang) 포맷 실패 — 원문 \"$raw\": ${e.message}")
                }
            }
        }
    }

    /**
     * 표의 키는 전부 코드가 쓴다 — 안 쓰는 키가 쌓이면 문구를 고칠 때 엉뚱한 줄을 고친다(감사 K8 · iOS K8: 예전엔 114 개 중
     * 49 개가 죽은 키였다). 동적 키(`uwb.err<번호>`·`code.<코드>`)는 만드는 자리를 따로 확인한다.
     */
    @Test fun everyKeyIsUsedBySources() {
        val src = java.io.File("src/main/kotlin").walkTopDown().filter { it.extension == "kt" }.joinToString("\n") { it.readText() }
        assertTrue("소스를 못 읽었다 — 작업 디렉터리가 모듈이 아니다", src.contains("object SdkLocalized"))
        val dynamic = Regex("""uwb\.err\d+|uwb\.errUnknown|code\.[EI]\d{4}""")
        val unused = SdkLocalized.table.keys.filter { !dynamic.matches(it) && !src.contains("\"$it\"") }
        assertEquals("안 쓰는 문구 키", emptyList<String>(), unused)
    }

    /** 코드마다 현재 언어 요약(`code.<코드>`)이 있다 — 없으면 화면 로그에 한국어 [SdkCode.summary] 가 섞인다(감사 SP-C8). */
    @Test fun everyCodeHasLocalizedSummary() {
        val codes: List<SdkCode> = SdkErrorCode.entries + SdkInfoCode.entries
        for (c in codes) assertTrue("code.${c.code} 없음", SdkLocalized.table.containsKey("code.${c.code}"))
        assertEquals(codes.size, SdkLocalized.table.keys.count { it.startsWith("code.") })
        SdkLocalized.language = "en"
        assertEquals("Positioning key unavailable", localizedSummary(SdkErrorCode.KEY_UNAVAILABLE))
        SdkLocalized.language = "ko"
        assertEquals(SdkErrorCode.KEY_UNAVAILABLE.summary, localizedSummary(SdkErrorCode.KEY_UNAVAILABLE))
    }

    /** `%lld` → Java `%d` — Long 인자가 그대로 정수로 찍힌다. */
    @Test fun uwbTrackingStartFormatsLongLongAsDecimal() {
        SdkLocalized.language = "ko"
        assertEquals("층 3 추적 시작 — UWB 측위", SdkLocalized.t("uwb.trackingStart", 3L))
    }

    /** `%lld` → Java `%d`, 다른 문구에서도 동일하게 동작한다. */
    @Test fun uwbTrackingStopFormatsLongLongAsDecimal() {
        SdkLocalized.language = "ko"
        assertEquals("층 3 이탈 — 다시 탐색", SdkLocalized.t("uwb.trackingStop", 3L))
    }

    /** `%@` 두 개 + `%lld` 하나가 순서대로 채워진다. */
    @Test fun uwbAreaFormatsTwoAtSignsAndLongLong() {
        SdkLocalized.language = "ko"
        assertEquals(
            "영역 A-1 \"구역1\" · 층 2 (엔진 판정)",
            SdkLocalized.t("uwb.area", "A-1", "구역1", 2L),
        )
    }

    /**
     * `%012llX`(폭 유지 + 길이 수식어 제거) · `%.1f`(그대로) · `%@`(→ `%s`) · `%u`(→ `%d`) 조합. 이 조합을 쓰던 문구
     * (provider.anchorScan)는 안 쓰여 지웠으므로(감사 K8) 변환기를 직접 본다.
     */
    @Test fun formatConverterKeepsWidthAndDropsLengthModifiers() {
        assertEquals("%012X %.1f %s %d %%", SdkLocalized.toJavaFormat("%012llX %.1f %@ %u %%"))
        assertEquals(
            "📡 앵커 0x00A1B2C3D4E5  RSSI -55.5  in range",
            String.format(java.util.Locale.ROOT, SdkLocalized.toJavaFormat("📡 앵커 0x%012llX  RSSI %.1f  %@"), 0xA1B2C3D4E5L, -55.5, "in range"),
        )
    }

    /**
     * [raw] 안의 Swift/C 스타일 지정자를 순서대로 훑어, 변환 문자에 맞는 더미 인자를 만든다
     * (정수 계열엔 [Long], 실수 계열엔 [Double], 문자열·`%@` 엔 [String]). `%%` 리터럴은
     * 인자를 쓰지 않으므로 건너뛴다.
     */
    private fun dummyArgsFor(raw: String): List<Any> =
        SPECIFIER.findAll(raw).mapNotNull { m ->
            when (m.value.last()) {
                '%' -> null
                '@', 's', 'S' -> "arg"
                'f', 'F', 'e', 'E', 'g', 'G', 'a', 'A' -> 1.5
                'c', 'C' -> 65
                else -> 7L
            }
        }.toList()

    private companion object {
        val SPECIFIER = Regex(
            "%[-+0 #]*\\d*(?:\\.\\d+)?(?:hh|ll|h|l|q|z|j|t|L)?[@diouxXeEfFgGaAcsp%]",
        )
    }
}
