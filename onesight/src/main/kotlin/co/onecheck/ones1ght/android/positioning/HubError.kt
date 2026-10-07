package co.onecheck.ones1ght.android.positioning

//
//  HubError.kt
//  측위 엔진 오류 번호(1~13) — 엔진 README 의 표를 이름으로 옮긴 것.
//
//  ⚠️ 예전엔 번호 목록이 자리마다 따로 박혀 있었다(시작을 되돌릴 번호 집합·사람이 풀어야 할 번호 집합·
//     E-코드 매핑 when·HUB_NO_LICENSE 같은 낱개 상수). 하나만 고치면 나머지가 어긋난다(감사 SP-C4 · iOS K15).
//     번호의 뜻은 여기 한 곳에서만 정한다.
//
//  포팅 원본: HubError.swift(iOS #55). 안드로이드 엔진에만 있는 번호는 9(매니페스트 선언 누락)·13(스캔 과다)다.
//

import co.onecheck.ones1ght.android.runtime.SdkErrorCode

internal enum class HubError(val code: Int) {
    /** 라이선스 미등록. */
    LICENSE_MISSING(1),

    /** 이미 측위 중 (호출 순서). */
    ALREADY_RUNNING(2),

    /** Bluetooth 불가(꺼짐·권한·미지원 — 엔진 문장으로 가른다). */
    BLUETOOTH_UNAVAILABLE(3),

    /** 그 층의 앵커 정보 없음. */
    ANCHORS_MISSING(4),

    /** DL-TDoA 세션 오류. */
    SESSION_FAILED(5),

    /** 영역 판정 오류. */
    AREA_JUDGE_FAILED(6),

    /** 위치 불가(권한·정밀도·서비스 꺼짐). */
    LOCATION_UNAVAILABLE(7),

    /** 정지 중 start (호출 순서). */
    START_WHILE_STOPPING(8),

    /** 매니페스트 RANGING 선언 누락 — 안드로이드 엔진 전용(앱을 고쳐야 한다). */
    MANIFEST_DECLARATION_MISSING(9),

    /** 서버가 라이선스 거부. */
    LICENSE_REJECTED(10),

    /** 라이선스 서버 미도달. */
    LICENSE_SERVER_UNREACHABLE(11),

    /** DL-TDoA 미지원 기기(OS 미달 포함). */
    DLTDOA_UNSUPPORTED(12),

    /** BLE 스캔 시작이 너무 잦음 — 안드로이드가 앱당 스캔 시작 횟수를 제한한다(30초 창). 안드로이드 엔진 전용. */
    SCAN_THROTTLED(13),
    ;

    /**
     * 시작 단계(아직 onStarted 가 안 옴)에서 나면 엔진이 **아예 뜨지 못한** 것인가 — 그때는 onStopped 가 오지
     * 않으므로 provider 가 스스로 되돌려야 한다. 2·8 은 호출 순서 문제라 엔진 상태를 바꾸지 않고, 4·5·6 은
     * 엔진이 뜬 뒤에 난다.
     */
    val abortsStart: Boolean
        get() = when (this) {
            LICENSE_MISSING, BLUETOOTH_UNAVAILABLE, LOCATION_UNAVAILABLE, MANIFEST_DECLARATION_MISSING,
            LICENSE_REJECTED, LICENSE_SERVER_UNREACHABLE, DLTDOA_UNSUPPORTED, SCAN_THROTTLED,
            -> true
            ALREADY_RUNNING, ANCHORS_MISSING, SESSION_FAILED, AREA_JUDGE_FAILED, START_WHILE_STOPPING -> false
        }

    /**
     * 사람이 풀어야 하는가 — 다시 켜 봐야 같은 자리에서 접힌다. 1 라이선스 미등록 · 3 Bluetooth · 7 위치 ·
     * 10 라이선스 거부(iOS 와 같다) + 안드로이드 전용 9 매니페스트 선언 누락(앱을 고쳐야 한다) · 12 미지원 기기
     * (기기를 바꿔야 한다). 11 라이선스 서버 미도달은 iOS 11 과 같이, 13 스캔 과다는 안드로이드 스캔 시작 제한이
     * 풀리면 되므로 다시 켜 볼 만하다.
     */
    val needsPerson: Boolean
        get() = when (this) {
            LICENSE_MISSING, BLUETOOTH_UNAVAILABLE, LOCATION_UNAVAILABLE, MANIFEST_DECLARATION_MISSING,
            LICENSE_REJECTED, DLTDOA_UNSUPPORTED,
            -> true
            else -> false
        }

    /**
     * SDK E-코드. `null` 은 "로그로만 남길 것" — 2·8 은 호출 순서 문제라 코드로 올리면 재시도마다 쌓여 진짜
     * 오류를 덮는다.
     *
     * [message] 는 엔진이 같이 준 문장이다. 오류 3 하나로 Bluetooth "꺼짐·권한·미지원" 이 다 오는데, 꺼짐은 빠른
     * 설정에서 켜면 풀리고 권한은 설정 앱에서 풀어야 해 할 일이 다르다 — 그래서 꺼짐만 따로 E2004 로 올린다
     * (iOS 0.1.24 와 같다). 엔진 1.1.0(1.1.1 도 같은 문장)은 꺼짐을 시작 때와 구동 중 모두 `bluetooth unavailable: powered off` 로
     * 준다. 권한은 `…: permission required — …`, 미지원은 `…: unsupported on this device` 다.
     *
     * 감사 SP-B9 — 기존 코드 안에서 할 일이 맞는 곳으로 옮겼다(새 E-코드는 공개 enum 확장이라 만들지 않는다):
     *  · 1 라이선스 미설정 · 10 라이선스 거부 → E1007(측위 키 문제). 엔진 라이선스는 콘솔이 주는 측위 키다 —
     *    E1002 「SDK 키 무효」로 올리면 멀쩡한 SDK 키(ock_sdk_)를 의심하게 했다. iOS 도 0.2.1 부터 E1007 이다.
     *  · 3 + `unsupported on this device` → E2002(미지원 기기). iOS 도 0.2.1 부터 E2002 다.
     *  · 7(위치 권한·정밀도·서비스 꺼짐)·9(매니페스트 RANGING 선언 누락)는 E2003 그대로 — 맞는 기존 코드가 없다.
     *  · 13(스캔 과다)은 **E3007(층 미탐지)**. 층은 BLE 스캔으로만 찾으므로 결과가 같다. 원인은 문맥
     *    (`engine=13 …`)에 남는다 — 잠시 뒤 다시 시작하면 풀린다.
     */
    fun sdkCode(message: String = ""): SdkErrorCode? = when (this) {
        LICENSE_MISSING -> SdkErrorCode.KEY_UNAVAILABLE
        BLUETOOTH_UNAVAILABLE -> when {
            message.contains(BLUETOOTH_POWERED_OFF, ignoreCase = true) -> SdkErrorCode.BLUETOOTH_OFF
            message.contains(BLUETOOTH_UNSUPPORTED, ignoreCase = true) -> SdkErrorCode.DEVICE_NOT_SUPPORTED
            else -> SdkErrorCode.PERMISSION_DENIED
        }
        ANCHORS_MISSING -> SdkErrorCode.LOCATORS_MISSING
        SESSION_FAILED -> SdkErrorCode.UWB_SESSION_FAILED
        AREA_JUDGE_FAILED -> SdkErrorCode.AREA_JUDGE_FAILED
        LOCATION_UNAVAILABLE -> SdkErrorCode.PERMISSION_DENIED
        MANIFEST_DECLARATION_MISSING -> SdkErrorCode.PERMISSION_DENIED
        LICENSE_REJECTED -> SdkErrorCode.KEY_UNAVAILABLE
        LICENSE_SERVER_UNREACHABLE -> SdkErrorCode.NETWORK
        DLTDOA_UNSUPPORTED -> SdkErrorCode.DEVICE_NOT_SUPPORTED
        SCAN_THROTTLED -> SdkErrorCode.FLOOR_NOT_DETECTED
        ALREADY_RUNNING, START_WHILE_STOPPING -> null
    }

    companion object {
        /** 엔진 오류 3 의 문장 중 "Bluetooth 꺼짐" 을 가르는 구절 — iOS 와 같은 판정. */
        private const val BLUETOOTH_POWERED_OFF = "powered off"

        /** 엔진 오류 3 의 문장 중 "Bluetooth 미지원 기기" 를 가르는 구절(엔진 1.1.0). */
        private const val BLUETOOTH_UNSUPPORTED = "unsupported on this device"

        /** 엔진 번호 → 이름. 모르는 번호(엔진 판올림 등)는 `null`. */
        fun of(code: Int): HubError? = entries.firstOrNull { it.code == code }
    }
}
