package co.onecheck.ones1ght.android.runtime

//
//  TrajectoryBuffer.kt
//  좌표 버퍼 — 인메모리(디스크 영속은 v2).
//
//  · append 로 축적 → flush 시 오래된 것부터 maxBatch([SdkLimits.MAX_PER_REQUEST])씩 잘라 전송(사양서 §6.5 상한)
//  · 전송 성공한 배치만 제거 — 실패하면 유지 → 다음 flush 때 재시도(사양서 §9)
//  · flush "트리거"(300건/60초/종료/백그라운드)는 SessionCoordinator 가 당긴다(다른 태스크)
//
//  포팅 원본: TrajectoryBuffer.swift.
//

import co.onecheck.ones1ght.android.model.PositionPoint

internal class TrajectoryBuffer(private val maxBatch: Int = SdkLimits.MAX_PER_REQUEST) {

    private val points = mutableListOf<PositionPoint>()
    private var isFlushing = false // 재진입 방지(트리거 중복 시 이중 전송 차단)

    /**
     * [clear] 할 때마다 1 증가 — 전송이 도는 사이에 비워졌는지 가른다. 비워졌으면 보낸 배치는 이미 버려진
     * 것이라 지울 것이 없다(iOS S1: 예전엔 빈 리스트에서 removeAt(0) 을 불러 앱이 죽었고, 그 사이 새로 쌓인
     * 좌표를 대신 지울 수도 있었다).
     */
    private var generation = 0

    val count: Int get() = points.size

    fun append(p: PositionPoint) {
        points.add(p)
    }

    /**
     * 쌓인 전부를 [maxBatch] 단위로 오래된 것부터 전송한다. 배치가 실패하면 남은 건 유지하고
     * 중단한다(다음 flush 에서 재시도). 재진입(이미 flush 진행 중)이면 아무 것도 하지 않고
     * false 를 돌려준다. 끝까지 비웠으면 true, 실패로 중단했으면 false.
     */
    suspend fun flush(send: suspend (List<PositionPoint>) -> Boolean): Boolean {
        if (isFlushing) return false
        isFlushing = true
        try {
            while (points.isNotEmpty()) {
                val batch = points.take(maxBatch)
                val gen = generation
                if (!send(batch)) return false // 실패 → 유지, 다음 기회에 재시도
                // 성공분만 제거(그 사이 append 된 건 보존). 전송 중 비워졌으면 지울 것이 없다.
                if (gen == generation) points.subList(0, minOf(batch.size, points.size)).clear()
            }
            return true
        } finally {
            isFlushing = false
        }
    }

    /** 쌓인 좌표를 전송 없이 버린다. */
    fun clear() {
        points.clear()
        generation += 1
    }
}
