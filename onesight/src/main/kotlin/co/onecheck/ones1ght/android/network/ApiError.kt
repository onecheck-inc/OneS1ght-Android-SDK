package co.onecheck.ones1ght.android.network

//
//  ApiError.kt
//  서버 응답 상태코드의 타입화 매핑 (사양서 §9). 포팅 원본: ApiClient.swift 의 `ApiError`,
//  SdkErrorCode.swift 243-253행(코드 매핑).
//
//  · equals/hashCode 는 같은 하위 타입 + 같은 필드일 때만 같다 (iOS `Equatable`).
//  · [ApiError] 는 `Exception` 하위 클래스라 Java 에서 `instanceof` 로 구분할 수 있다.
//  · ⚠️ 키·좌표 같은 값은 [message] 에 담지 않는다. 담는 것은 "무엇이" 잘못됐는지까지다.
//

import co.onecheck.ones1ght.android.runtime.SdkErrorCode
import java.io.IOException

/** [detail] 이 있으면 " — $detail" 을 덧붙인다 — 없거나 빈 문자열이면 그대로 둔다. */
private fun describe(base: String, detail: String?): String =
    if (detail.isNullOrEmpty()) base else "$base — $detail"

public sealed class ApiError(message: String) : Exception(message) {

    /** 401 — 키 무효/폐기 → 측위 중단(재시도 무의미). */
    public class InvalidKey(public val detail: String?) :
        ApiError(describe("SDK 키가 무효하거나 폐기됨", detail)) {
        override fun equals(other: Any?): Boolean = other is InvalidKey && other.detail == detail
        override fun hashCode(): Int = detail.hashCode()
    }

    /** 403 — 다른 테넌트 자원. */
    public class Forbidden(public val detail: String?) :
        ApiError(describe("접근 권한 없음 (다른 고객사 자원)", detail)) {
        override fun equals(other: Any?): Boolean = other is Forbidden && other.detail == detail
        override fun hashCode(): Int = detail.hashCode()
    }

    /** 404 — (floors) 층에 존 없음 = 정상 분기. */
    public class NotFound(public val detail: String?) :
        ApiError(describe("대상을 찾을 수 없음", detail)) {
        override fun equals(other: Any?): Boolean = other is NotFound && other.detail == detail
        override fun hashCode(): Int = detail.hashCode()
    }

    /** 422 — 페이로드 문제(개발 버그). */
    public class Unprocessable(public val detail: String?) :
        ApiError(describe("서버가 요청을 거절함", detail)) {
        override fun equals(other: Any?): Boolean = other is Unprocessable && other.detail == detail
        override fun hashCode(): Int = detail.hashCode()
    }

    /** 5xx 등 그 외. */
    public class Server(public val status: Int, public val detail: String?) :
        ApiError(describe("서버 오류 (HTTP $status)", detail)) {
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
        ApiError("네트워크 실패 (${cause.javaClass.simpleName})") {
        override fun equals(other: Any?): Boolean = other is Network && other.cause == cause
        override fun hashCode(): Int = cause.hashCode()
    }

    /**
     * 응답 JSON 형태 불일치. [detail] 에 무엇을 못 읽었는지 담는다 — 이게 없으면 화면에
     * "decoding" 넉 자만 떠서 어느 응답의 어느 필드인지 알 길이 없다.
     */
    public class Decoding(public val detail: String?) :
        ApiError(describe("응답 해석 실패", detail)) {
        override fun equals(other: Any?): Boolean = other is Decoding && other.detail == detail
        override fun hashCode(): Int = detail.hashCode()
    }

    /** iOS `SdkErrorCode.swift` 243-253행과 동일한 매핑. [NotFound] 는 대개 계약 불일치라 E5003 이다. */
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
