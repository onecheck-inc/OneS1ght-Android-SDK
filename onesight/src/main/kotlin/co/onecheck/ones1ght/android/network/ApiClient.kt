package co.onecheck.ones1ght.android.network

//
//  ApiClient.kt
//  서버 통신 — OkHttp 경량 클라이언트 (사양서 §6 엔드포인트 5종 + config·profiles·logs = 11종)
//
//  · 모든 요청: X-SDK-Key 헤더 + JSON. JWT/토큰 교환 없음 (사양서 §2)
//  · 상태코드 → 타입화 에러([ApiError], 에러 본문 {detail} 파싱)
//  · HTTPS + TLS 검증 그대로(우회 금지). ⚠️ 키는 로그·에러 메시지에 노출하지 않는다
//
//  포팅 원본: ApiClient.swift
//

import co.onecheck.ones1ght.android.internal.SdkJson
import co.onecheck.ones1ght.android.model.ReqPositionBulk
import co.onecheck.ones1ght.android.model.ReqProfile
import co.onecheck.ones1ght.android.model.ReqSdkLogs
import co.onecheck.ones1ght.android.model.ReqVerify
import co.onecheck.ones1ght.android.model.ReqZoneEvent
import co.onecheck.ones1ght.android.model.ResBuildings
import co.onecheck.ones1ght.android.model.ResFloorConfig
import co.onecheck.ones1ght.android.model.ResPositionBulk
import co.onecheck.ones1ght.android.model.ResProfile
import co.onecheck.ones1ght.android.model.ResProfileCreate
import co.onecheck.ones1ght.android.model.ResProfileDelete
import co.onecheck.ones1ght.android.model.ResSdkConfig
import co.onecheck.ones1ght.android.model.ResSdkLogs
import co.onecheck.ones1ght.android.model.ResVerify
import co.onecheck.ones1ght.android.model.ResZoneEvent
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private val JSON_MEDIA_TYPE = "application/json".toMediaType()

/** 오류 본문 `{ "detail": "..." }` — 형태가 다르면 파싱에 실패해도(→ null) 에러 매핑 자체는 유지한다. */
@Serializable
private data class ErrorBody(val detail: String? = null)

/** 헤더·바디 없이 GET/DELETE 를 포함한 모든 요청에 10초 타임아웃을 건다. */
private fun defaultHttp(): OkHttpClient =
    OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

/**
 * 서버 통신 클라이언트 — 콘솔 SDK API 11종.
 *
 * [apiKey] 는 헤더에만 실린다 — 저장·로그 금지.
 */
public class ApiClient private constructor(
    public val apiKey: String,
    public val baseUrl: String,
    http: OkHttpClient,
) {
    /**
     * 공개 생성자에는 OkHttpClient 를 받지 않는다 — okhttp 는 implementation 의존이라 공개 시그니처에
     * 나오면 고객 컴파일 클래스패스에 없는 타입이 된다(JavaApiSurfaceTest (e)). HTTP 클라이언트를 갈아끼우는
     * 건 모듈 안(테스트)에서만 [create] 로 한다 — 그 생성자를 internal 로 두면 JVM 에선 public 이라 Java 에 보여서
     * private 으로 막았다.
     */
    @JvmOverloads
    public constructor(apiKey: String, baseUrl: String = DEFAULT_BASE_URL) : this(apiKey, baseUrl, defaultHttp())

    /**
     * 같은 모듈의 SSE(Task 6)가 재사용한다. `http.newBuilder()` 로 스트리밍용 타임아웃(예:
     * `readTimeout(0, …)`)만 바꿔 쓸 것 — 이 클라이언트 자체의 기본 타임아웃은 10초다.
     */
    internal val http: OkHttpClient = http

    public companion object {
        public const val DEFAULT_BASE_URL: String = "https://console.ones1ght.com/api/sdk/v1"

        /** HTTP 클라이언트를 주입하는 모듈 내부 팩토리 — @JvmSynthetic 이라 Java 에는 안 보인다. */
        @JvmSynthetic
        internal fun create(apiKey: String, baseUrl: String, http: OkHttpClient): ApiClient = ApiClient(apiKey, baseUrl, http)
    }

    // MARK: - 엔드포인트 11종

    /** POST /auth/verify — 키 검증 + 클라 등록(초기화 1회). */
    internal suspend fun verify(req: ReqVerify): ResVerify = post("/auth/verify", req)

    /**
     * GET /config — 관련 키 조회(구글맵·공간 서비스 모바일/파트너 키·공간 서비스 주소).
     * 실패해도 초기화를 막지 않는다(호출부가 폴백한다).
     */
    internal suspend fun config(): ResSdkConfig = get("/config")

    /** GET /positioning/buildings — 건물·층 목록(측위 활성화 시 1회). */
    internal suspend fun buildings(): ResBuildings = get("/positioning/buildings")

    /** GET /positioning/floors/{floor_id} — 층 존 설정(층 진입 시 해당 층만). */
    internal suspend fun floorConfig(floorId: String): ResFloorConfig = get("/positioning/floors/$floorId")

    /** POST /events/zone — 존 입장/체류/퇴장(판정 즉시). */
    internal suspend fun sendZoneEvent(req: ReqZoneEvent): ResZoneEvent = post("/events/zone", req)

    /** POST /positioning/logs — 동선 좌표 벌크(300건/60초/종료). */
    internal suspend fun sendPositionLogs(req: ReqPositionBulk): ResPositionBulk = post("/positioning/logs", req)

    /** POST /profiles — 프로필 생성, 서버가 profile_id 를 발급한다. */
    internal suspend fun createProfile(attrs: Map<String, String>): ResProfileCreate =
        post("/profiles", ReqProfile(attrs))

    /** GET /profiles/{id} */
    internal suspend fun getProfile(id: String): ResProfile = get("/profiles/$id")

    /** PUT /profiles/{id} — 속성 전체 교체. */
    internal suspend fun putProfile(id: String, attrs: Map<String, String>): ResProfile =
        send("/profiles/$id", "PUT", ReqProfile(attrs))

    /** DELETE /profiles/{id} — 본문 없음(Content-Type 헤더는 그대로 붙인다). */
    internal suspend fun deleteProfile(id: String): ResProfileDelete =
        perform(request("/profiles/$id", "DELETE", null))

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
            .url(baseUrl.trimEnd('/') + path)
            .method(method, body?.toRequestBody(JSON_MEDIA_TYPE))
            .header("X-SDK-Key", apiKey)
            .header("Content-Type", "application/json")
            .build()

    private suspend inline fun <reified R> perform(req: Request): R = performJsonRequest(http, req)
}

// MARK: - 콘솔·공간 서비스 공유 헬퍼
//
// 상태코드→에러 매핑·IOException→Network·디코드→Decoding 은 SpaceServiceClient(공간 조회,
// Task 5)도 그대로 써야 한다 — 여긴 유일한 정본이라 거기서 다시 만들지 않는다.

/** `IOException` 은 [ApiError.Network] 로 바꾼다 — `enqueue` 콜백을 코루틴으로 잇는다. */
@JvmSynthetic // 최상위 internal 함수는 이름이 망글링되지 않아 Java 에 보인다
internal suspend fun executeHttpRequest(http: OkHttpClient, req: Request): Pair<Int, ByteArray> =
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
                            cont.resume(it.code to (it.body?.bytes() ?: ByteArray(0)))
                        }
                    } catch (e: IOException) {
                        // 헤더는 받았지만 본문을 읽는 중 끊긴 경우도 전송 실패다.
                        cont.resumeWithException(ApiError.Network(e))
                    }
                }
            },
        )
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
 */
@JvmSynthetic // 최상위 internal 함수는 이름이 망글링되지 않아 Java 에 보인다
internal suspend inline fun <reified R> performJsonRequest(http: OkHttpClient, req: Request): R {
    val (status, bytes) = executeHttpRequest(http, req)
    if (status in 200 until 300) {
        return try {
            SdkJson.decodeFromString<R>(bytes.decodeToString())
        } catch (e: SerializationException) {
            throw ApiError.Decoding(detail = e.message)
        }
    }
    throw apiErrorForStatus(status, errorDetailFrom(bytes))
}
