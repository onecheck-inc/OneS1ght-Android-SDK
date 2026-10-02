package co.onecheck.ones1ght.android.network

//
//  ApiError.kt
//  서버 응답 상태코드의 타입화 매핑 (사양서 §9). 포팅 원본: ApiClient.swift 의 `ApiError`,
//  SdkErrorCode.swift 243-253행(코드 매핑).
//
//  · equals/hashCode 는 같은 하위 타입 + 같은 필드일 때만 같다 (iOS `Equatable`).
//  · [ApiError] 는 `Exception` 하위 클래스라 Java 에서 `instanceof` 로 구분할 수 있다.
//  · ⚠️ 키·좌표 같은 값은 [message] 에 담지 않는다. 담는 것은 "무엇이" 잘못됐는지까지다.
//  · [message] 는 Java getMessage() 로 고객 로그에 그대로 닿는 공개 문자열이라 [co.onecheck.ones1ght.android.SdkError]
//    와 같이 **영어 + 코드**로 둔다(감사 SF-C4: 예전엔 한국어라 ja/en 고객 로그에 한국어가 섞였다). 형식은
//    `설명 (E-코드)` + 있으면 ` — detail`. 로그 문구의 다국어는 SdkLocalized 의 몫이다.
//  · ⚠️ iOS 는 아직 한국어다(ApiClient.swift description) — 맞추려면 iOS 도 같은 문구로 바꾼다.
//

import co.onecheck.ones1ght.android.runtime.SdkErrorCode
import java.io.IOException

/** `설명 (코드)` 에 [detail] 이 있으면 " — $detail" 을 덧붙인다 — 없거나 빈 문자열이면 그대로 둔다. */
private fun describe(base: String, code: SdkErrorCode, detail: String?): String =
    if (detail.isNullOrEmpty()) "$base (${code.code})" else "$base (${code.code}) — $detail"

public sealed class ApiError(message: String) : Exception(message) {

    /** 401 — 키 무효/폐기 → 측위 중단(재시도 무의미). */
    public class InvalidKey(public val detail: String?) :
        ApiError(describe("Invalid or revoked SDK key", SdkErrorCode.INVALID_KEY, detail)) {
        override fun equals(other: Any?): Boolean = other is InvalidKey && other.detail == detail
        override fun hashCode(): Int = detail.hashCode()
    }

    /** 403 — 다른 테넌트 자원. */
    public class Forbidden(public val detail: String?) :
        ApiError(describe("Access denied — the resource belongs to another tenant", SdkErrorCode.FORBIDDEN, detail)) {
        override fun equals(other: Any?): Boolean = other is Forbidden && other.detail == detail
        override fun hashCode(): Int = detail.hashCode()
    }

    /** 404 — (floors) 층에 존 없음 = 정상 분기. */
    public class NotFound(public val detail: String?) :
        ApiError(describe("Not found", SdkErrorCode.UNPROCESSABLE, detail)) {
        override fun equals(other: Any?): Boolean = other is NotFound && other.detail == detail
        override fun hashCode(): Int = detail.hashCode()
    }

    /** 422 — 페이로드 문제(개발 버그). */
    public class Unprocessable(public val detail: String?) :
        ApiError(describe("Request rejected by the server", SdkErrorCode.UNPROCESSABLE, detail)) {
        override fun equals(other: Any?): Boolean = other is Unprocessable && other.detail == detail
        override fun hashCode(): Int = detail.hashCode()
    }

    /** 5xx 등 그 외. */
    public class Server(public val status: Int, public val detail: String?) :
        ApiError(describe("Server error HTTP $status", SdkErrorCode.SERVER, detail)) {
        override fun equals(other: Any?): Boolean =
            other is Server && other.status == status && other.detail == detail
        override fun hashCode(): Int = 31 * status + (detail?.hashCode() ?: 0)
    }

    /**
     * 오프라인·타임아웃 등 전송 실패.
     *
     * ⚠️ iOS 는 `URLError.code.rawValue`(정수 코드)를 메시지에 담지만, JVM `IOException`
     * 계열에는 그런 숫자 코드가 없다 — 대신 예외 클래스 이름으로 "무엇이" 실패했는지 남긴다
     * (`SocketTimeoutException`·`UnknownHostException` 등).
     */
    public class Network(public override val cause: IOException) :
        ApiError("Network failure: ${cause.javaClass.simpleName} (${SdkErrorCode.NETWORK.code})") {
        override fun equals(other: Any?): Boolean = other is Network && other.cause == cause
        override fun hashCode(): Int = cause.hashCode()
    }

    /**
     * 응답 JSON 형태 불일치. [detail] 에 무엇을 못 읽었는지 담는다 — 이게 없으면 화면에
     * "decoding" 넉 자만 떠서 어느 응답의 어느 필드인지 알 길이 없다.
     */
    public class Decoding(public val detail: String?) :
        ApiError(describe("Failed to decode the response", SdkErrorCode.DECODING, detail)) {
        override fun equals(other: Any?): Boolean = other is Decoding && other.detail == detail
        override fun hashCode(): Int = detail.hashCode()
    }

    /**
     * iOS `SdkErrorCode.swift` 와 동일한 매핑. [NotFound] 는 대개 계약 불일치라 E5003 이다 — 「클라이언트가 없는 자원을
     * 찾았다」를 따로 가르려면 새 하위 타입·코드가 필요해(공개 sealed 계층 변경) 0.2 로 미룬다(감사 SF-C4).
     */
    public val code: SdkErrorCode
        get() = when (this) {
            is InvalidKey -> SdkErrorCode.INVALID_KEY
            is Forbidden -> SdkErrorCode.FORBIDDEN
            is NotFound -> SdkErrorCode.UNPROCESSABLE
            is Unprocessable -> SdkErrorCode.UNPROCESSABLE
            is Server -> SdkErrorCode.SERVER
            is Network -> SdkErrorCode.NETWORK
            is Decoding -> SdkErrorCode.DECODING
        }
}
