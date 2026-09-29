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

    /** 패키징된 JSON 이 109 키 × 3언어(ko/ja/en) 인지 — 리소스 유실·언어 누락을 잡는다. */
    @Test fun jsonHas109KeysTimesThreeLanguages() {
        val table = SdkLocalized.table
        assertEquals(109, table.size)
        for ((key, entry) in table) {
            assertEquals("$key 의 언어 집합이 ko/ja/en 이 아니다", setOf("ko", "ja", "en"), entry.keys)
        }
        assertTrue(table.isNotEmpty())
    }

    /**
     * 108 키 × 3언어 전부를, 원문(Swift/C 스타일) 지정자에서 뽑아낸 타입에 맞는 더미
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

    /** `%012llX`(폭 유지 + 길이 수식어 제거) · `%.1f`(그대로) · `%@`(→ `%s`) 조합. */
    @Test fun providerAnchorScanFormatsHexFloatAndAtSign() {
        SdkLocalized.language = "ko"
        assertEquals(
            "📡 앵커 0x00A1B2C3D4E5  RSSI -55.5  in range",
            SdkLocalized.t("provider.anchorScan", 0xA1B2C3D4E5L, -55.5, "in range"),
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
