package co.onecheck.ones1ght.android.runtime

//
//  SdkLogBuffer.kt
//  SDK 로그 버퍼 — 관리자가 콘솔 로그 분석기에서 볼 줄을 모아 배치 전송한다.
//
//  · 좌표 버퍼(TrajectoryBuffer)와 같은 구조이되 임계가 작다(50건). 로그는 양이 적고 진단이
//    목적이라 빨리 도달해야 한다.
//  · ERROR 는 즉시 flush 를 예약한다 — 앱이 죽기 전에 남겨야 원인을 안다.
//  · 전송 실패가 앱 동작을 막지 않는다. 로그는 부가 기능이고, 실패분은 버린다(좌표와 달리
//    재시도로 붙들면 진짜 데이터가 밀린다).
//  · 폭주 방어 — hardLimit 을 넘으면 오래된 것부터 버린다(최근 상황이 진단에 더 쓸모 있다).
//
//  포팅 원본: SdkLogBuffer.swift. append 가 예약하는 flush 는 주입된 [scope] 위에서 돈다
//  (iOS 의 `Task { await flush() }` 자리 — 코어 상태를 바꾸는 다른 곳들과 마찬가지로 주입된
//  디스패처/스코프 한 곳에서만 돈다).
//

import co.onecheck.ones1ght.android.model.SdkLogEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal class SdkLogBuffer(
    private val threshold: Int = 50,
    private val maxBatch: Int = 500,
    private val hardLimit: Int = 2000,
    private val send: suspend (List<SdkLogEntry>) -> Boolean,
    private val scope: CoroutineScope,
    /**
     * 지금 보낼 수 있는가(귀속할 프로필이 있는가). 아니면 떼지 않고 붙들어 둔다 — [hardLimit] 안에서. 코어가
     * 프로필이 생기는 순간(identify) [flush] 를 부른다(iOS S13 · 감사 SP-B7: 예전엔 떼어 낸 뒤 버려 초기화 중
     * E1007 이 콘솔에 안 올라갔다).
     */
    private val canSend: () -> Boolean = { true },
) {

    private val entries = mutableListOf<SdkLogEntry>()
    private var isFlushing = false // 재진입 방지

    val count: Int get() = entries.size

    /** 로그 적재. ERROR 거나 [threshold] 에 닿으면 flush 를 예약한다. */
    fun append(e: SdkLogEntry) {
        entries.add(e)
        if (entries.size > hardLimit) {
            repeat(entries.size - hardLimit) { entries.removeAt(0) } // 오래된 것부터 버림
        }
        if (e.level == "ERROR" || entries.size >= threshold) {
            scope.launch { flush() }
        }
    }

    /**
     * 쌓인 전부를 [maxBatch] 단위로 전송한다. 배치는 성공·실패와 무관하게 먼저 떼어낸 뒤
     * 보낸다 — 실패한 배치는 버린다(재시도하지 않는다).
     */
    suspend fun flush() {
        if (isFlushing || entries.isEmpty() || !canSend()) return
        isFlushing = true
        try {
            while (entries.isNotEmpty()) {
                val batch = entries.take(maxBatch)
                repeat(batch.size) { entries.removeAt(0) } // 성공·실패와 무관하게 먼저 뗀다
                if (!send(batch)) break // 실패하면 나머지도 이번엔 포기
            }
        } finally {
            isFlushing = false
        }
    }
}
