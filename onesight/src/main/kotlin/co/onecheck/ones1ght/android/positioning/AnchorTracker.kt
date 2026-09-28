package co.onecheck.ones1ght.android.positioning

//
//  AnchorTracker.kt
//  측위 통신 진단 — "등록한 로케이터 중 실제로 몇 대의 신호가 잡히고 있나".
//
//  iOS 현재 판(외부 엔진)은 앵커별 수신을 알 수 없어 canAttributePerAnchor=false 였지만,
//  안드로이드는 레인징 결과마다 앵커 주소가 오므로 b804f3b 판의 진단을 그대로 되살린다
//  (등록 vs 수신 차집합 → E4003 부활, spec §2·§5).
//
//  ⚠️ [seen] 은 엔진 스레드에서, [diagnostic] 은 메인에서 불린다 — 그래서 이 클래스만은
//     메서드를 동기화한다(측정마다 메인으로 넘기면 초당 수십 번의 디스패치가 생긴다).
//
//  포팅 원본: b804f3b UwbPositioningProvider.swift 의 seenAddresses·diagnostic.
//

internal class AnchorTracker {

    private var registered: Set<Int> = emptySet()
    private val received = HashSet<Int>()

    /** 등록 기준(콘솔 로케이터 짧은주소) 교체. 수신 기록은 건드리지 않는다. */
    @Synchronized
    fun register(addresses: Set<Int>) {
        registered = addresses.toSet()
    }

    /** 앵커 신호 1건 수신. */
    @Synchronized
    fun seen(address: Int) {
        received.add(address)
    }

    /** 세션을 새로 열 때 — 수신 기록만 비운다(등록 기준은 층 설정이라 유지). */
    @Synchronized
    fun clearSeen() {
        received.clear()
    }

    @Synchronized
    fun diagnostic(hasFix: Boolean): PositioningDiagnostic {
        val matched = registered.intersect(received)
        return PositioningDiagnostic(
            registeredCount = registered.size,
            receivedCount = received.size,
            matchedCount = matched.size,
            missingAddresses = (registered - received).sorted(),
            hasFix = hasFix,
            canAttributePerAnchor = true,
        )
    }

    companion object {
        /**
         * UWB 주소 바이트 → 짧은주소(하위 2바이트, 빅엔디언). 콘솔 로케이터가 `0xABCD` 로
         * 등록되므로 8바이트 확장 주소여도 마지막 2바이트만 쓴다. 2바이트 미만이면 `null`.
         */
        fun shortAddress(bytes: ByteArray?): Int? {
            if (bytes == null || bytes.size < 2) return null
            val hi = bytes[bytes.size - 2].toInt() and 0xFF
            val lo = bytes[bytes.size - 1].toInt() and 0xFF
            return (hi shl 8) or lo
        }
    }
}
