package co.onecheck.ones1ght.android.runtime

//
//  SdkLocalized.kt  (SDK 로그/메시지 다국어)
//
//  OS 언어가 일본어면 "ja", 영어면 "en", 그 외엔 "ko".
//  문구는 리소스 i18n/SdkLocalization.json 에 { "키": { "ko":…, "ja":…, "en":… } } 로 관리.
//  (앱과 독립 — SDK 가 자기 리소스를 클래스패스에서 읽어 스스로 변환한다.)
//
//  포팅 원본: SdkLocalized.swift.
//

import co.onecheck.ones1ght.android.internal.SdkJson
import kotlinx.serialization.decodeFromString
import java.util.Locale

internal object SdkLocalized {

    /**
     * 앱이 SDK 로그·안내에 쓸 언어로 지정한 값. `null` 이면 [deviceLanguage] 를 따른다.
     *
     * 앱이 자체 언어 설정을 가지고 있으면 기기 언어와 어긋난다 — 그때 SDK 만 기기 언어로
     * 남아 로그 창에 다른 말이 섞인다. 앱이 자기 선택을 알려줄 길이 필요하다.
     */
    internal var language: String? = null

    /**
     * 기기 언어 조회 — 테스트가 주입할 수 있게 함수로 둔다.
     * 기본값: [Locale.getDefault] → ja/en/그 외 ko.
     */
    internal var deviceLanguage: () -> String = {
        when (Locale.getDefault().language) {
            "ja" -> "ja"
            "en" -> "en"
            else -> "ko"
        }
    }

    /**
     * 로드된 문구 테이블: `[키: [언어: 문구]]`. 클래스패스 리소스에서 1회만 읽는다.
     *
     * module 가시성(internal)이라 테스트가 리소스 유실·언어 누락(108 키 × 3언어)을 직접
     * 대조할 수 있다.
     */
    internal val table: Map<String, Map<String, String>> by lazy { loadTable() }

    private fun loadTable(): Map<String, Map<String, String>> {
        val stream = SdkLocalized::class.java.getResourceAsStream(
            "/co/onecheck/ones1ght/android/i18n/SdkLocalization.json",
        ) ?: return emptyMap()
        val text = stream.use { it.readBytes().toString(Charsets.UTF_8) }
        return SdkJson.decodeFromString(text)
    }

    /**
     * 키 → 현재 언어 문구를 반환한다. 현재 언어([language] 없으면 [deviceLanguage]) 에
     * 없으면 ko, 그것도 없으면(키 자체가 없으면) 키 문자열 그대로 돌려준다.
     *
     * `%@`(Swift 원본 포맷 표기)를 `%s` 로 바꾼 뒤 [args] 를 [String.format] 으로 채운다.
     */
    internal fun t(key: String, vararg args: Any?): String {
        val lang = language ?: deviceLanguage()
        val entry = table[key]
        val raw = entry?.get(lang) ?: entry?.get("ko") ?: key
        return String.format(Locale.ROOT, raw.replace("%@", "%s"), *args)
    }
}
