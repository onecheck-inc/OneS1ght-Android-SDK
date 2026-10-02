package co.onecheck.ones1ght.android.positioning

//
//  PositioningProvider.kt
//  측위 엔진 주입 계약 — SDK는 UWB를 모른다.
//
//  내장 구현: 측위 엔진(층 탐지·UWB 측위·영역 판정)을 감싼 어댑터(UwbPositioningProvider). 커스텀 provider 는
//  delegate·start()·stop() 만 채우면 되고 나머지는 기본 구현이 있다(선택 채택). provider 는 delegate 로 필수 3종
//  (onPosition·onZone·onEnter) + 선택 2종(onReport·onStoppedUnexpectedly)을 올리고, 코어가 서버 계약에 맞춰 전송한다.
//
//  스레드 계약: SDK 는 이 인터페이스의 멤버를 **메인 스레드**(코어 디스패처)에서 부른다. 구현도 delegate 를 메인
//  스레드에서 불러야 한다 — 코어 상태에는 잠금이 없다(엔진 스레드에서 오는 콜백은 provider 가 메인으로 넘긴다).
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
 * 코어가 `setFloorMap` 으로 받은 층(로케이터·세션·존)을 여기 담아 [PositioningProvider.apply] (config) 로 꽂는다.
 * 앱이 직접 만들어 넣을 수도 있다.
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

    /**
     * 콘솔 건물·층 ID 반영 (선택 채택 — 기본 no-op). 코어가 `setFloorMap` 으로 정한 층을 넣는다 — 층을 비우면
     * 빈 문자열 두 개가 온다. 가동 중에도 불린다(층 전환).
     */
    public fun apply(buildingId: String, floorId: String) {}

    /**
     * 측위 설정(앵커·세션·존) 주입 (선택 채택 — 기본 no-op). start 전에도, 가동 중에도 불린다(층 전환·층 해제).
     *
     * 넘어온 값이 그 층의 **전부**다 — 빈 [PositioningConfig.anchors] 는 「앵커 없음(층 해제 포함)」이다. 구역만 바꿀
     * 때는 코어가 [applyZones] 를 부른다.
     */
    public fun apply(config: PositioningConfig) {}

    /**
     * 구역만 바꾼다 — 앵커·세션은 그대로 둔다 (선택 채택 — 기본은 `apply(PositioningConfig(zones = zones))`).
     * 코어의 구역 새로고침(refreshZones)이 부른다.
     *
     * 왜 따로 있나(감사 SP-C9): 예전엔 구역 갱신도 apply(config) 에 빈 앵커로 실어 보내, 빈 앵커가 「안 바뀜」과 「층
     * 해제」를 함께 뜻했다. 그래서 내장 provider 는 빈 앵커를 무시했고, 층을 해제해도 옛 등록 로케이터 수가 남아
     * 수신 점검(E4002) 문맥이 틀렸다. 기본 구현이 예전 호출과 같아 기존 provider 는 고칠 것이 없다.
     */
    public fun applyZones(zones: List<Zone>) {
        apply(PositioningConfig(zones = zones))
    }

    /**
     * 판정 영역이 바뀌었다 — 엔진이 지오펜스를 다시 읽게 하라 (선택 채택 — 기본 no-op).
     *
     * ⚠️ 공짜가 아니다 — 다시 뜨는 동안 좌표가 끊긴다. 영역이 실제로 바뀌었을 때만 부를 것.
     */
    public fun reloadGeofences() {}

    /** 일시정지 중인가 (선택 채택 — 기본 false). */
    public val isPaused: Boolean
        get() = false

    /**
     * 좌표 **소비**만 멈춘다 — 엔진은 계속 돈다(선택 채택 — 기본 no-op). `FloorSession.pause()` 가 이걸 부른다 —
     * 특정 provider 로 형변환하지 않으므로 커스텀 provider 도 같은 길로 멈춘다(iOS K14 가 고친 것과 같은 모양).
     * 생명주기 정지·재시작(백그라운드)은 일시정지를 **유지**해야 한다 — 코어가 복귀 때 다시 건다.
     */
    public fun pause() {}

    /** 일시정지 해제 (선택 채택 — 기본 no-op). */
    public fun resume() {}
}

/**
 * [PositioningProvider] 가 부르는 콜백 — SDK 코어가 구현해 서버 계약으로 옮긴다. **메인 스레드에서 부른다.**
 */
public interface PositioningProviderDelegate {
    /** 좌표 갱신(측위 fix) — SDK 가 다운샘플·버퍼링 → positioning/logs. [floorId] 가 null 이면 setFloorMap 의 층으로 귀속한다. */
    public fun onPosition(provider: PositioningProvider, coordinates: Coordinates, floorId: String?, atMs: Long)

    /** 존 진입/체류/이탈 판정 — SDK 가 events/zone 전송 (체류 DWELL 은 기기 안에서만 쓰므로 보내지 않는다). */
    public fun onZone(provider: PositioningProvider, zoneId: String, status: ZoneEventStatus, floorId: String?, atMs: Long)

    /**
     * 입장 트리거(빌딩 진입 감지) — 통지만. SDK 는 이걸로 아무것도 하지 않는다(건물·층 조회는 앱의 몫).
     * 선택 채택 — 기본 no-op(iOS `didEnter` 와 같다. 예전엔 필수라 코어가 빈 구현을 들고 있었다 — 감사 SP-C8).
     */
    public fun onEnter(provider: PositioningProvider, buildingId: String) {}

    /**
     * 엔진이 진단 코드를 올린다 — SDK 가 onDebugLog + 서버 로그(E-코드)로 옮긴다.
     * 선택 채택 — 기본 no-op 이라 Mock 은 구현하지 않아도 된다.
     */
    public fun onReport(provider: PositioningProvider, code: SdkErrorCode, context: String) {}

    /**
     * **켜 달라고 했는데 엔진이 스스로 꺼졌다** — 시작이 접혔거나(권한·라이선스·Bluetooth 등), 돌다가 엔진
     * 오류로 멈췄다. 부르는 쪽(SDK 코어)이 [PositioningProvider.stop] 한 경우에는 오지 않는다.
     *
     * 이게 없던 동안 코어는 세션을 "측위 중" 으로 둔 채 몰랐다. 그러면 앱의 `begin()` 은 "이미 측위 중" 으로
     * 삼켜지고, 앱을 껐다 켜기 전엔 측위가 돌아오지 않았다(감사 SP-B1 · iOS #54 와 같은 수정).
     *
     * @param retryable 다시 켜 볼 만한가. 권한 거부·Bluetooth 꺼짐·라이선스 거부·미지원 기기처럼 사람이 풀어야
     *   하는 것은 false.
     * @param context 진단용 한 줄(로그·서버 E-코드 문맥에 실린다).
     *
     * 선택 채택 — 기본 no-op.
     */
    public fun onStoppedUnexpectedly(provider: PositioningProvider, retryable: Boolean, context: String) {}
}
