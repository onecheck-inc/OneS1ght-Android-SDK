package co.onecheck.ones1ght.android.network

//
//  ApiClient.kt
//  서버 통신 — OkHttp 경량 클라이언트 — verify·config·존 이벤트·좌표·프로필 4종·로그 = 9종
//
//  · 모든 요청: X-SDK-Key 헤더 + JSON. JWT/토큰 교환 없음 (사양서 §2)
//  · 상태코드 → 타입화 에러([ApiError], 에러 본문 {detail} 파싱)
//  · HTTPS + TLS 검증 그대로(우회 금지). ⚠️ 키는 로그·에러 메시지에 노출하지 않는다
//
//  포팅 원본: ApiClient.swift
//

import co.onecheck.ones1ght.android.OneS1ght
import co.onecheck.ones1ght.android.internal.SdkJson
import co.onecheck.ones1ght.android.model.ReqPositionBulk
import co.onecheck.ones1ght.android.model.ReqProfile
import co.onecheck.ones1ght.android.model.ReqSdkLogs
import co.onecheck.ones1ght.android.model.ReqVerify
import co.onecheck.ones1ght.android.model.ReqZoneEvent
import co.onecheck.ones1ght.android.model.ResPositionBulk
import co.onecheck.ones1ght.android.model.ResProfile
import co.onecheck.ones1ght.android.model.ResProfileCreate
import co.onecheck.ones1ght.android.model.ResProfileDelete
import co.onecheck.ones1ght.android.model.ResSdkConfig
import co.onecheck.ones1ght.android.model.ResSdkLogs
import co.onecheck.ones1ght.android.model.ResVerify
import co.onecheck.ones1ght.android.model.ResZoneEvent
import co.onecheck.ones1ght.android.runtime.SdkTimeouts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private val JSON_MEDIA_TYPE = "application/json".toMediaType()

/** 오류 본문 `{ "detail": "..." }` — 형태가 다르면 파싱에 실패해도(→ null) 에러 매핑 자체는 유지한다. */
@Serializable
private data class ErrorBody(val detail: String? = null)

/** GET/DELETE 를 포함한 모든 요청에 [SdkTimeouts.API_SECONDS] 타임아웃을 건다. */
private fun defaultHttp(): OkHttpClient =
    OkHttpClient.Builder()
        .connectTimeout(SdkTimeouts.API_SECONDS, TimeUnit.SECONDS)
        .readTimeout(SdkTimeouts.API_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(SdkTimeouts.API_SECONDS, TimeUnit.SECONDS)
        .build()

/**
 * 서버 통신 클라이언트 — 콘솔 SDK API 9종. **SDK 내부 전용이다.**
 *
 * 0.0.6 까지는 생성자·[key]·[base] 가 공개(`apiKey`·`baseUrl`)였다(감사 SF-C1 · iOS K4 — iOS 0.1.24 도 같았다).
 * 엔드포인트는 이미 전부 internal 이라 고객이 이 객체로 할 수 있는 일이 없었다. 타입 이름만 남긴 것은 옛
 * `ApiClient.DEFAULT_BASE_URL` 을 쓰던 코드가 경고만 받고 계속 컴파일되게 하려는 것이다(iOS 와 같은 모양).
 * 생성은 모듈 안에서 [create] 로만 한다 — 생성자를 internal 로 두면 JVM 에선 public 이라 Java 에 보여서
 * private 으로 막았다.
 *
 * 키는 헤더에만 실린다 — 저장·로그 금지.
 */
public class ApiClient private constructor(
    /** SDK 키 — 모듈 안에서만 읽는다(공개로 올리면 SDK 키가 고객사 코드에 노출된다). */
    internal val key: String,
    /** 콘솔 SDK API 주소. */
    internal val base: String,
    http: OkHttpClient,
) {
    /**
     * 같은 모듈의 SSE(Task 6)가 재사용한다. `http.newBuilder()` 로 스트리밍용 타임아웃(예:
     * `readTimeout(0, …)`)만 바꿔 쓸 것 — 이 클라이언트 자체의 기본 타임아웃은 [SdkTimeouts.API_SECONDS] 다.
     */
    internal val http: OkHttpClient = http

    public companion object {
        /** 0.0.6 까지의 자리 — `OneS1ght.DEFAULT_BASE_URL` 로 옮겼다(iOS `ApiClient.defaultBaseURL` → `OneS1ght.defaultBaseURL`). */
        @Deprecated(
            "OneS1ght.DEFAULT_BASE_URL 로 옮겼다",
            ReplaceWith("OneS1ght.DEFAULT_BASE_URL", "co.onecheck.ones1ght.android.OneS1ght"),
            level = DeprecationLevel.WARNING,
        )
        public const val DEFAULT_BASE_URL: String = OneS1ght.DEFAULT_BASE_URL

        /** HTTP 클라이언트를 주입하는 모듈 내부 팩토리 — @JvmSynthetic 이라 Java 에는 안 보인다. */
        @JvmSynthetic
        internal fun create(apiKey: String, baseUrl: String, http: OkHttpClient = defaultHttp()): ApiClient =
            ApiClient(apiKey, baseUrl, http)
    }

    // MARK: - 엔드포인트 9종

    /** POST /auth/verify — 키 검증 + 클라 등록(초기화 1회). */
    internal suspend fun verify(req: ReqVerify): ResVerify = post("/auth/verify", req)

    /**
     * GET /config — 관련 키 조회(구글맵·공간 서비스 모바일/파트너 키·공간 서비스 주소).
     * 실패해도 초기화를 막지 않는다(호출부가 폴백한다).
     */
    internal suspend fun config(): ResSdkConfig = get("/config")

    /** POST /events/zone — 존 입장/체류/퇴장(판정 즉시). */
    internal suspend fun sendZoneEvent(req: ReqZoneEvent): ResZoneEvent = post("/events/zone", req)

    /** POST /positioning/logs — 동선 좌표 벌크(300건/60초/종료). */
    internal suspend fun sendPositionLogs(req: ReqPositionBulk): ResPositionBulk = post("/positioning/logs", req)

    /** POST /profiles — 프로필 생성, 서버가 profile_id 를 발급한다. */
    internal suspend fun createProfile(attrs: Map<String, String>): ResProfileCreate =
        post("/profiles", ReqProfile(attrs))

    /** GET /profiles/{id} */
    internal suspend fun getProfile(id: String): ResProfile = get("/profiles/${pathSegment(id)}")

    /** PUT /profiles/{id} — 속성 전체 교체. */
    internal suspend fun putProfile(id: String, attrs: Map<String, String>): ResProfile =
        send("/profiles/${pathSegment(id)}", "PUT", ReqProfile(attrs))

    /** DELETE /profiles/{id} — 본문 없음(Content-Type 헤더는 그대로 붙인다). */
    internal suspend fun deleteProfile(id: String): ResProfileDelete =
        perform(request("/profiles/${pathSegment(id)}", "DELETE", null))

    /**
     * POST /logs — 관리자가 콘솔 로그 분석기에서 볼 줄을 적재한다. 서버가 느슨하게 받도록
     * 설계돼 있어(레벨 정규화·2000자 절단·시각 결측 보정) 실패해도 앱 동작에는 영향이 없다.
     */
    internal suspend fun sendLogs(req: ReqSdkLogs): ResSdkLogs = post("/logs", req)

    // MARK: - 내부 공통

    private suspend inline fun <reified R> get(path: String): R = perform(request(path, "GET", null))

    private suspend inline fun <reified B, reified R> post(path: String, body: B): R = send(path, "POST", body)

    private suspend inline fun <reified B, reified R> send(path: String, method: String, body: B): R {
        val json = SdkJson.encodeToString(body)
        return perform(request(path, method, json.toByteArray(Charsets.UTF_8)))
    }

    /** `baseUrl.trimEnd('/') + path` 로 조립한다 — path 는 항상 "/" 로 시작해야 한다. */
    private fun request(path: String, method: String, body: ByteArray?): Request =
        Request.Builder()
            .url(base.trimEnd('/') + path)
            .method(method, body?.let { if (method == "POST") OneShotBody(it) else it.toRequestBody(JSON_MEDIA_TYPE) })
            .header("X-SDK-Key", key)
            .header("Content-Type", "application/json")
            .build()

    private suspend inline fun <reified R> perform(req: Request): R = performJsonRequest(http, req)
}

/**
 * POST 본문 — **한 번 보낸 요청은 OkHttp 가 조용히 다시 보내지 않게** 한다(`isOneShot`).
 *
 * OkHttp 는 기본값(retryOnConnectionFailure)으로, 재사용하던 연결이 응답 전에 끊기면 요청을 새 연결로 다시 보낸다 —
 * 서버가 이미 받아 처리한 POST 라도. 그러면 구역 이벤트·좌표·프로필 생성이 SDK 도 모르게 두 번 들어가
 * 방문·체류 집계가 부풀고 주인 없는 프로필이 생긴다(2026-10-02 CI 조사에서 확인한 동작). 재시도는 SDK 가
 * 명시적으로 하는 것(구역 이벤트 1회·좌표 backoff)만 둔다 — iOS URLSession 도 보낸 POST 를 다시 보내지 않는다.
 * 연결 단계 실패(요청을 아직 안 보냄)의 자동 복구는 그대로다. GET·PUT·DELETE 는 멱등이라 건드리지 않는다.
 */
private class OneShotBody(private val bytes: ByteArray) : RequestBody() {
    override fun contentType() = JSON_MEDIA_TYPE
    override fun contentLength() = bytes.size.toLong()
    override fun isOneShot() = true
    override fun writeTo(sink: BufferedSink) { sink.write(bytes) }
}

// MARK: - 콘솔·공간 서비스 공유 헬퍼
//
// 상태코드→에러 매핑·IOException→Network·디코드→Decoding 은 SpaceServiceClient(공간 조회,
// Task 5)도 그대로 써야 한다 — 여긴 유일한 정본이라 거기서 다시 만들지 않는다.

/**
 * 응답 본문 상한 — 도면(base64 PNG)이 가장 크다. 넘으면 끝까지 읽지 않고 E5005 로 끊는다(감사 SF-A13 —
 * 상한이 없으면 수 MB 이상의 본문이 그대로 메모리에 올라 OOM 으로 갈 수 있었다).
 */
internal const val MAX_RESPONSE_BYTES: Long = 32L * 1024 * 1024

/**
 * 경로 변수 하나를 **한 경로 조각**으로 인코딩한다 — `/ ? #` 등은 퍼센트 인코딩(감사 SF-A16: 예전엔 문자열을
 * 그대로 붙여 ID 에 `../` 가 들어가면 다른 엔드포인트를 쳤다). `.`·`..`·빈 값은 URL 정규화가 경로를
 * 올려 버리므로 인코딩으로 막을 수 없다 — 그런 ID 의 자원은 없으니 [ApiError.NotFound] 로 끝낸다.
 */
@JvmSynthetic // 최상위 internal 함수는 이름이 망글링되지 않아 Java 에 보인다
internal fun pathSegment(id: String): String {
    if (id.isEmpty() || id == "." || id == "..") throw ApiError.NotFound("invalid path segment")
    return HttpUrl.Builder().scheme("https").host("h").addPathSegment(id).build().encodedPath.removePrefix("/")
}

/**
 * `IOException` 은 [ApiError.Network] 로 바꾼다 — `enqueue` 콜백을 코루틴으로 잇는다. 본문은 OkHttp 스레드에서
 * 읽고, [maxBytes] 를 넘으면 [ApiError.Decoding] 으로 끊는다.
 */
@JvmSynthetic // 최상위 internal 함수는 이름이 망글링되지 않아 Java 에 보인다
internal suspend fun executeHttpRequest(
    http: OkHttpClient,
    req: Request,
    maxBytes: Long = MAX_RESPONSE_BYTES,
): Pair<Int, ByteArray> =
    suspendCancellableCoroutine { cont ->
        val call = http.newCall(req)
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(
            object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    cont.resumeWithException(ApiError.Network(e))
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        response.use {
                            val bytes = readCapped(it, maxBytes)
                            if (bytes == null) {
                                cont.resumeWithException(ApiError.Decoding("response body over $maxBytes bytes"))
                            } else {
                                cont.resume(it.code to bytes)
                            }
                        }
                    } catch (e: IOException) {
                        // 헤더는 받았지만 본문을 읽는 중 끊긴 경우도 전송 실패다.
                        cont.resumeWithException(ApiError.Network(e))
                    }
                }
            },
        )
    }

/** 본문을 [maxBytes] 까지만 읽는다 — 넘으면 null(끝까지 읽지 않는다). */
private fun readCapped(response: Response, maxBytes: Long): ByteArray? {
    val body = response.body ?: return ByteArray(0)
    if (body.contentLength() > maxBytes) return null
    val source = body.source()
    source.request(maxBytes + 1)
    if (source.buffer.size > maxBytes) return null
    return source.buffer.readByteArray()
}

/** 상태코드 → [ApiError] 매핑(사양서 §9). 200~299 는 호출부가 직접 디코딩한다. */
internal fun apiErrorForStatus(status: Int, detail: String?): ApiError =
    when (status) {
        401 -> ApiError.InvalidKey(detail)
        403 -> ApiError.Forbidden(detail)
        404 -> ApiError.NotFound(detail)
        422 -> ApiError.Unprocessable(detail)
        else -> ApiError.Server(status = status, detail = detail)
    }

/** 에러 본문 `{"detail": "..."}` 에서 detail 추출(형태가 다르면 null — 에러 매핑 자체는 유지). */
internal fun errorDetailFrom(bytes: ByteArray): String? =
    try {
        SdkJson.decodeFromString<ErrorBody>(bytes.decodeToString()).detail
    } catch (e: SerializationException) {
        null
    }

/**
 * 요청 실행 + 2xx 디코딩 + 오류 매핑을 한 번에 한다 — [ApiClient] 와 `SpaceServiceClient`
 * (공간 조회, Task 5)가 공유한다. 타임아웃은 호출부가 건넨 [http] 그대로 쓴다.
 *
 * 디코딩은 [Dispatchers.Default] 에서 한다 — 부르는 쪽은 코어 디스패처(운영: 메인)라, 수 MB 도면 JSON 을
 * 거기서 풀면 ANR 이었다(감사 SF-A13). 객체가 아닌 본문 등으로 kotlinx 가 던지는 IllegalArgumentException
 * (SerializationException 의 부모)도 [ApiError.Decoding] 으로 옮긴다 — 문서에 없는 예외가 새지 않게(SF-A4).
 */
@JvmSynthetic // 최상위 internal 함수는 이름이 망글링되지 않아 Java 에 보인다
internal suspend inline fun <reified R> performJsonRequest(http: OkHttpClient, req: Request): R {
    val (status, bytes) = executeHttpRequest(http, req)
    if (status in 200 until 300) {
        return try {
            withContext(Dispatchers.Default) { SdkJson.decodeFromString<R>(bytes.decodeToString()) }
        } catch (e: IllegalArgumentException) {
            throw ApiError.Decoding(detail = e.message)
        }
    }
    throw apiErrorForStatus(status, errorDetailFrom(bytes))
}
