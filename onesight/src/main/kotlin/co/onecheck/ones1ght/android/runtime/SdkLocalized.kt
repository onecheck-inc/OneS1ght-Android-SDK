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
     *
     * `@Volatile`: 여러 스레드(메인·네트워크·위치 콜백)에서 읽고, 앱이 아무 스레드에서나
     * 쓸 수 있다 — 쓰기가 즉시 다른 스레드의 읽기에 보이지 않으면 SDK 문구가 일시적으로
     * 옛 언어에 묶인다.
     */
    @Volatile
    internal var language: String? = null

    /**
     * 기기 언어 조회 — 테스트가 주입할 수 있게 함수로 둔다.
     * 기본값: [Locale.getDefault] → ja/en/그 외 ko.
     *
     * `@Volatile`: [language] 와 같은 이유.
     */
    @Volatile
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

    /** 리소스가 없거나(패키징 사고) JSON 이 깨졌으면(디코딩 실패) 빈 맵 — iOS `try?` 와 같은 무너지지-않기. */
    private fun loadTable(): Map<String, Map<String, String>> =
        try {
            val stream = SdkLocalized::class.java.getResourceAsStream(
                "/co/onecheck/ones1ght/android/i18n/SdkLocalization.json",
            ) ?: return emptyMap()
            val text = stream.use { it.readBytes().toString(Charsets.UTF_8) }
            SdkJson.decodeFromString(text)
        } catch (e: Exception) {
            emptyMap()
        }

    /**
     * Swift/C 스타일 포맷 지정자를 Java [java.util.Formatter] 가 받아들이는 형태로 바꾼다.
     *
     * - `%@`(Swift 문자열 보간) → `%s`
     * - 길이 수식어(`hh`·`h`·`ll`·`l`·`q`·`z`·`j`·`t`·`L`, 예: `%lld`·`%012llX`)는 통째로 버린다 —
     *   Java 에는 정수 크기별 변환 문자가 따로 없다(`Long` 인자만 넘기면 된다).
     * - `u`/`U`(부호 없는 정수)는 `d` 로 바꾼다 — Java `Formatter` 에는 `%u` 변환 자체가 없다.
     * - `%.2f` 같은 폭·정밀도·나머지 변환 문자는 그대로 둔다.
     */
    private val FORMAT_SPECIFIER = Regex(
        "%([-+0 #]*)(\\d*)(\\.\\d+)?(?:hh|ll|h|l|q|z|j|t|L)?([@diouxXeEfFgGaAcsp%])",
    )

    private fun toJavaFormat(raw: String): String =
        FORMAT_SPECIFIER.replace(raw) { m ->
            val flags = m.groupValues[1]
            val width = m.groupValues[2]
            val precision = m.groupValues[3]
            val conversion = when (val c = m.groupValues[4]) {
                "@" -> "s"
                "u", "U" -> "d"
                else -> c
            }
            "%$flags$width$precision$conversion"
        }

    /**
     * 키 → 현재 언어 문구를 반환한다. 현재 언어([language] 없으면 [deviceLanguage]) 에
     * 없으면 ko, 그것도 없으면(키 자체가 없으면) 키 문자열 그대로 돌려준다.
     *
     * [toJavaFormat] 으로 지정자를 정리한 뒤 [args] 를 [String.format] 으로 채운다.
     */
    internal fun t(key: String, vararg args: Any?): String {
        val lang = language ?: deviceLanguage()
        val entry = table[key]
        val raw = entry?.get(lang) ?: entry?.get("ko") ?: key
        return String.format(Locale.ROOT, toJavaFormat(raw), *args)
    }
}
