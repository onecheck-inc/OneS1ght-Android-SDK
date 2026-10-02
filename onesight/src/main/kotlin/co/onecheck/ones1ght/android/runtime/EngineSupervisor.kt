package co.onecheck.ones1ght.android.runtime

//
//  EngineSupervisor.kt
//  엔진이 **스스로** 꺼졌을 때 — 다시 켜 볼지, 세션을 닫을지.
//
//  SessionCoordinator 에서 떼어 냈다(감사 SP-C2 · iOS K7 — 같은 이름). 언제 이 판단을 할 수 있는지(세션이 살아
//  있는가·그 provider 인가)와 닫는 일 자체는 코디네이터가 정하고, 여기는 횟수·대기·로그만 맡는다.
//  스레드: 코어 디스패처([scope])에서만 부른다.
//
//  닫는 이유: 세션을 "측위 중" 으로 둔 채 엔진만 죽어 있으면 앱의 `begin()` 이 "이미 측위 중" 으로 삼켜져, 앱을
//  껐다 켜기 전엔 측위가 안 돌아왔다(감사 SP-B1 · iOS #54). 닫으면 `FloorSession.isRunning` 이 false 가 되어 앱이
//  알고(onStopped) 다시 연다.
//
//  포팅 원본: EngineSupervisor.swift(iOS #55).
//

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal class EngineSupervisor(
    private val scope: CoroutineScope,
    /** 다시 켜 보는 간격 — 이만큼 해도 안 되면 세션을 닫는다(iOS #54 와 같은 값: 3·10·30초). */
    private val delaysMs: List<Long>,
    private val reporter: SdkReporter,
) {
    /** 지금까지 다시 켠 횟수 — 좌표가 한 번 나오거나 포그라운드로 돌아오면 0 으로. */
    private var attempts = 0
    private var job: Job? = null

    /** 다시 살아났다(좌표가 나왔다)·새 기회다(포그라운드 복귀) — 다음 고장은 처음부터 센다. */
    fun resetAttempts() {
        attempts = 0
    }

    /** 대기 중인 재시도를 멈춘다(백그라운드). */
    fun cancel() {
        job?.cancel()
        job = null
    }

    /** 세션이 시작·종료됐다 — 재시도도 횟수도 지운다. */
    fun reset() {
        cancel()
        attempts = 0
    }

    /**
     * 엔진이 스스로 꺼졌다 — 다시 켜 보거나(재시도 가능·횟수 남음), [giveUp] 으로 세션을 닫게 한다.
     *
     * @param beforeRetry 다시 켜기로 정한 직후(로그 전에) 부른다 — 코디네이터가 사용자가 건 일시정지를 기억한다.
     * @param canRestart 대기가 끝난 순간에 다시 켜도 되는가(그 사이 세션이 끝났거나 provider 가 바뀌었으면 false).
     * @param inForeground 대기가 끝난 순간 앱이 화면에 있는가 — 배경이면 켜지 않는다(포그라운드 복귀가 대신 켠다).
     */
    fun handleUnexpectedStop(
        retryable: Boolean,
        context: String,
        beforeRetry: () -> Unit,
        canRestart: () -> Boolean,
        inForeground: () -> Boolean,
        restart: () -> Unit,
        giveUp: () -> Unit,
    ) {
        cancel()
        val attempt = attempts
        if (!retryable || attempt >= delaysMs.size) {
            // 사람이 풀어야 하는 원인(권한·Bluetooth·라이선스)은 엔진이 이미 제 코드(E2003·E2004 등)로 올렸다 —
            // E4001(ERROR)을 덧붙이면 같은 일이 「고장」 으로 두 번 찍힌다. 재시도를 다 쓴 것만 올린다.
            val gaveUp = SdkLocalized.t("coord.engineGaveUp", context)
            if (retryable) {
                reporter.report(SdkErrorCode.UWB_SESSION_FAILED, "engine stopped, session closed — $context", message = gaveUp)
            } else {
                reporter.log(LogLevel.WARN, gaveUp)
            }
            giveUp()
            return
        }
        attempts += 1
        beforeRetry()
        val delayMs = delaysMs[attempt]
        val total = delaysMs.size
        reporter.report(
            SdkErrorCode.UWB_SESSION_FAILED,
            "engine stopped, retry ${attempt + 1}/$total in ${delayMs / 1000}s — $context",
            message = SdkLocalized.t("coord.engineRetry", attempt + 1, total, delayMs / 1000),
        )
        job = scope.launch {
            delay(delayMs)
            job = null
            if (!canRestart()) return@launch
            // 배경이면 켜지 않는다 — UWB 가 안 돈다. 포그라운드 복귀(onForegroundResumed)가 대신 켠다.
            if (!inForeground()) return@launch
            restart()
        }
    }
}
