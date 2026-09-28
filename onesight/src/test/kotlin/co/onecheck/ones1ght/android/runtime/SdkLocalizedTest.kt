package co.onecheck.ones1ght.android.runtime

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    /** 패키징된 JSON 이 108 키 × 3언어(ko/ja/en) 인지 — 리소스 유실·언어 누락을 잡는다. */
    @Test fun jsonHas108KeysTimesThreeLanguages() {
        val table = SdkLocalized.table
        assertEquals(108, table.size)
        for ((key, entry) in table) {
            assertEquals("$key 의 언어 집합이 ko/ja/en 이 아니다", setOf("ko", "ja", "en"), entry.keys)
        }
        assertTrue(table.isNotEmpty())
    }
}
