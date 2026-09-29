package co.onecheck.ones1ght.android.positioning

//
//  PositioningProvider.kt
//  측위 엔진 주입 계약 — SDK는 UWB를 모른다.
//
//  실제 구현: 측위 엔진(층 탐지·UWB 측위·영역 판정)을 감싼 어댑터(UwbPositioningProvider)가 이
//  인터페이스를 구현해 콜백 3종(+선택 1종)을 쏜다. 패키지는 그 결과를 서버 계약에 맞춰 전송만 한다.
//
//  포팅 원본: PositioningProvider.swift.
//

import co.onecheck.ones1ght.android.model.Coordinates
import co.onecheck.ones1ght.android.model.Zone
import co.onecheck.ones1ght.android.model.ZoneEventStatus
import co.onecheck.ones1ght.android.runtime.SdkErrorCode

/**
 * 측위 설정 — 측위 엔진에 주입하는 "콘센트".
 *
 * ★ 소스 갈아끼우는 자리: 지금은 앱이 공간 서비스에서 받아 채워 넣고, 나중에 콘솔이
 *   도면·앵커·존을 프록시하면 그쪽에서 받아 같은 자리에 꽂는다. 소스가 바뀌어도 이
 *   구조체와 apply(config:)는 고정.
 *
 * [anchors] 는 [DoubleArray] 값을 담아 데이터 클래스 기본 equals/hashCode(참조 비교)가
 * 틀린 값을 주므로 내용 비교로 직접 구현한다.
 */
public data class PositioningConfig @JvmOverloads constructor(
    /** 앵커: 짧은주소(UWB MAC 뒤 2바이트, 예: 0xABCD) → 도면 로컬 미터 좌표(x, y, z) */
    public val anchors: Map<Int, DoubleArray> = emptyMap(),
    /** UWB 세션(= networkIdentifier). 층마다 다름 */
    public val sessionId: Int? = null,
    /** 이 층의 존 — 온디바이스 판정 대상 (비면 판정이 돌지 않는다) */
    public val zones: List<Zone> = emptyList(),
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PositioningConfig) return false
        if (sessionId != other.sessionId) return false
        if (zones != other.zones) return false
        if (anchors.keys != other.anchors.keys) return false
        return anchors.all { (key, value) -> other.anchors[key]?.contentEquals(value) == true }
    }

    override fun hashCode(): Int {
        var result = sessionId?.hashCode() ?: 0
        result = 31 * result + zones.hashCode()
        var anchorsHash = 0
        for ((key, value) in anchors) {
            // 순서 무관 합산 — 두 맵이 같은 항목을 다른 순서로 들고 있어도 같은 해시가 나와야 한다.
            anchorsHash += key.hashCode() xor value.contentHashCode()
        }
        result = 31 * result + anchorsHash
        return result
    }
}

/**
 * 측위 통신 진단 — "등록한 로케이터 중 실제로 몇 대의 신호가 잡히고 있나".
 *
 * ⚠️ 이 값만으로 고장을 단정하지 않는다. 앵커 세트는 마스터 1대와 서브 여러 대로 이루어지고,
 * 마스터가 살아 있는 한 서브가 빠져도 측위는 계속된다 — 감도가 떨어질 뿐이다. 그래서
 * 미수신은 에러가 아니라 유지보수 신호(WARN)로 다룬다.
 */
public data class PositioningDiagnostic @JvmOverloads constructor(
    /** 등록된 로케이터 수 */
    public val registeredCount: Int,
    /** 실제로 신호가 잡힌 수 */
    public val receivedCount: Int,
    /** 등록 ∩ 수신 — 좌표를 아는 유효 로케이터 */
    public val matchedCount: Int,
    /** 등록됐는데 신호가 없는 주소 */
    public val missingAddresses: List<Int>,
    /** 측위 엔진이 좌표를 실제로 내고 있는가 */
    public val hasFix: Boolean,
    /**
     * 앵커 하나하나를 구분해서 답할 수 있는가.
     *
     * ⚠️ 이 값이 없으면 진단이 조용히 죽는다. 앵커별 상태를 못 주는 엔진은 [missingAddresses]
     * 를 항상 비우고 [matchedCount] 를 0 으로 둘 수밖에 없는데, 그러면 "미수신이 있으면
     * 알린다"·"신호는 잡히는데 좌표가 없으면 알린다" 두 조건이 구조적으로 성립 불가가 되어
     * 좌표가 안 나와도 아무 로그가 안 남는다. 그래서 "모른다"를 0 으로 위장하지 않고 이
     * 플래그로 드러낸다.
     */
    public val canAttributePerAnchor: Boolean = true,
) {
    /**
     * 로그에 실을 한 줄. 주소는 등록된 표기(0xABCD)를 그대로 쓴다 — 현장에서 기기 라벨과
     * 대조해야 하는 값이라 형식을 바꾸면 못 찾는다.
     */
    public val missingLabel: String
        get() = missingAddresses.joinToString(separator = ",") { String.format("0x%04X", it) }
}

/**
 * 측위 제공자 계약 — 실제 구현(호스트 쪽 UWB 어댑터)이 이 인터페이스를 구현한다.
 */
public interface PositioningProvider {
    public var delegate: PositioningProviderDelegate?

    public fun start()
    public fun stop()

    /** 측위 통신 진단 (선택 채택 — 기본 null). 진단을 낼 수 없는 provider(Mock 등)는 구현하지 않으면 된다. */
    public val positioningDiagnostic: PositioningDiagnostic?
        get() = null

    /** 콘솔 건물·층 반영 (선택 채택 — 기본 no-op). SDK 코어가 buildings 응답에서 뽑아 넣어준다. */
    public fun apply(buildingId: String, floorId: String) {}

    /** 측위 설정(앵커·세션) 주입 (선택 채택 — 기본 no-op). start 전에 호출. */
    public fun apply(config: PositioningConfig) {}

    /**
     * 판정 영역이 바뀌었다 — 엔진이 지오펜스를 다시 읽게 하라 (선택 채택 — 기본 no-op).
     *
     * ⚠️ 공짜가 아니다 — 다시 뜨는 동안 좌표가 끊긴다. 영역이 실제로 바뀌었을 때만 부를 것.
     */
    public fun reloadGeofences() {}

    public val isPaused: Boolean
        get() = false

    public fun pause() {}

    public fun resume() {}
}

/**
 * [PositioningProvider] 가 부르는 콜백 — SDK 코어가 구현해 서버 계약으로 옮긴다.
 */
public interface PositioningProviderDelegate {
    /** 좌표 갱신(측위 fix) — SDK가 버퍼링 → positioning/logs */
    public fun onPosition(provider: PositioningProvider, coordinates: Coordinates, floorId: String?, atMs: Long)

    /** 존 진입/체류/이탈 판정 — SDK가 events/zone 전송 */
    public fun onZone(provider: PositioningProvider, zoneId: String, status: ZoneEventStatus, floorId: String?, atMs: Long)

    /** 입장 트리거(빌딩 진입 감지) — SDK가 buildings/floors 로드 시작 */
    public fun onEnter(provider: PositioningProvider, buildingId: String)

    /**
     * 엔진이 진단 코드를 올린다 — SDK 가 onDebugLog + 서버 로그(E-코드)로 옮긴다.
     * 선택 채택 — 기본 no-op 이라 Mock 은 구현하지 않아도 된다.
     */
    public fun onReport(provider: PositioningProvider, code: SdkErrorCode, context: String) {}
}
