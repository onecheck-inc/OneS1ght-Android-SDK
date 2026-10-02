package co.onecheck.ones1ght.android.runtime

//
//  SdkReporter.kt
//  SDK 로그의 두 갈래 — 화면 로그(onDebugLog)와 서버 로그(콘솔 로그 분석기).
//
//  SessionCoordinator 에서 떼어 냈다(감사 SP-C2 · iOS K7 — 같은 이름). 코디네이터가 한 타입에 책임 열 가지
//  넘게 지고 있었고, SP-B7(프로필 전 로그 유실)·K9(같은 사건 두 줄) 같은 결함이 이 경계에 몰려 있었다.
//  스레드: 코디네이터와 같은 코어 디스패처([scope])에서만 부른다.
//
//  포팅 원본: SdkReporter.swift(iOS #55).
//

import co.onecheck.ones1ght.android.OneS1ght
import co.onecheck.ones1ght.android.internal.Iso8601
import co.onecheck.ones1ght.android.model.ReqSdkLogs
import co.onecheck.ones1ght.android.model.SdkLogEntry
import co.onecheck.ones1ght.android.network.ApiClient
import co.onecheck.ones1ght.android.network.ApiError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope

internal class SdkReporter(
    private val api: ApiClient,
    scope: CoroutineScope,
    private val clock: () -> Long,
    /** 서버 로그를 귀속할 프로필 — 없으면 보내지 않고 붙든다(SdkLogBuffer.canSend — 감사 SP-B7 · iOS S13). */
    private val profileId: () -> String?,
) {
    /** 화면 로그 훅 — OneS1ght.onDebugLog 로 이어진다. */
    var onLog: ((LogLevel, String) -> Unit)? = null

    private val buffer = SdkLogBuffer(send = ::send, scope = scope, canSend = { profileId() != null })

    /** 흐름 기록. 등급을 안 적으면 LOG — 대다수가 그것이라 생략할 수 있게 둔다. */
    fun log(msg: String) = log(LogLevel.LOG, msg)

    fun log(level: LogLevel, msg: String) {
        onLog?.invoke(level, msg)
    }

    /**
     * 코드 붙은 사건을 남긴다 — 화면 로그(onDebugLog) **한 줄** + 서버(코드 + 문맥).
     *
     * 서버로는 문구를 보내지 않는다: 읽는 사람이 관리자라 콘솔이 관리자 화면 언어로 렌더링한다. 화면 줄은
     * `[코드] 문구 — 문맥` 이고, 문구는 [message](현재 언어)가 있으면 그것을, 없으면 코드 요약(현재 언어 —
     * [localizedSummary])을 쓴다. 줄의 등급은 코드의 세기(ERROR·WARN·INFO)와 같다.
     *
     * ⚠️ 호출부가 문구 로그를 따로 또 남기지 않는다. 예전엔 report 가 이미 한 줄을 남기는데 호출부가 번역 문구를
     *    한 줄 더 찍어 같은 사건이 두 번 보였다(iOS K9 — 같은 결정).
     */
    fun report(code: SdkCode, ctx: String = "", message: String? = null) {
        val text = message ?: localizedSummary(code)
        log(code.level.toLogLevel(), "[${code.code}] $text${if (ctx.isEmpty()) "" else " — $ctx"}")
        buffer.append(SdkLogEntry(code = code.code, level = code.level.wire, message = ctx, at = Iso8601.format(clock())))
    }

    /** 서버 통신 실패를 코드로 옮겨 남긴다. ApiError 가 아니면 network 로 본다. */
    fun reportApi(error: Throwable, ctx: String = "", message: String? = null) {
        report((error as? ApiError)?.code ?: SdkErrorCode.NETWORK, ctx, message)
    }

    /** 붙들어 둔 로그를 지금 보낸다(세션 종료·프로필 연결). */
    suspend fun flush() {
        buffer.flush()
    }

    private suspend fun send(batch: List<SdkLogEntry>): Boolean {
        // profileId 가 없으면 귀속할 곳이 없다 — 버퍼가 canSend 로 붙들고 있다가 identify 때 보낸다(SP-B7 · iOS S13).
        val profileId = profileId() ?: return false
        val req = ReqSdkLogs(profileId, SdkPlatform.NAME, OneS1ght.SDK_VERSION, batch)
        return try {
            api.sendLogs(req)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
    }
}
