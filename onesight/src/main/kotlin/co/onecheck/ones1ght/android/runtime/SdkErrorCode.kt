package co.onecheck.ones1ght.android.runtime

//
//  SdkErrorCode.kt
//  로그 코드 — 앱 로그·콘솔 로그 분석기·트러블슈팅 문서가 같은 값을 가리키게 하는 표식.
//
//  · 사람이 읽는 문구는 언어·표현이 바뀌지만 코드는 안 바뀐다. 고객 문의가 들어왔을 때
//    "E2003" 하나로 원인을 특정할 수 있어야 한다.
//  · 서버로는 코드와 문맥만 보낸다. 문구는 콘솔이 관리자 화면 언어로 렌더링한다 —
//    로그를 읽는 사람은 기기 사용자가 아니라 관리자라, 기기 언어를 따르면 일본 사용자의
//    에러가 일본어로 쌓여 한국인 관리자가 읽게 된다.
//  · 앞 한 자리 = 계열. 새 코드는 계열 안에서 뒤 번호만 늘린다 — 한 번 쓴 번호는
//    재사용하지 않는다(옛 로그의 의미가 바뀌면 안 된다).
//
//      1xxx  초기화·인증      앱 개발자 / 테넌트 관리자
//      2xxx  기기·권한        최종 사용자
//      3xxx  공간·설정        테넌트 관리자 (콘솔·공간 서비스)
//      4xxx  측위             테넌트 관리자 (현장 하드웨어)
//      5xxx  전송             앱 개발자 / 통합 관리자
//
//  글자(E/I)는 계열이고, 등급(level)은 알람 세기다. 둘은 별개 축이다.
//
//   · ERROR — 고장났거나 사람이 고쳐야 한다. 연동 실수·통신 실패·설정 누락으로 기능이 죽음.
//   · WARN  — 계속 동작하지만 알아야 한다. 일부 기능만 못 쓰거나, 확인이 필요한 상태.
//   · INFO  — 그냥 사실이다. 고칠 것이 없다.
//
//  ⚠️ 정상 상태를 ERROR 로 찍지 말 것. 미지원 기기·꺼 둔 설정·구역 없는 층은 고장이
//     아니다. 정상 경로에서 매번 울리는 ERROR 는 그 코드의 의미를 잃게 만들고, 진짜
//     고장을 그 소음 속에 묻는다.
//
//  포팅 원본: SdkErrorCode.swift. 등급·문구는 iOS 원본과 동일하다(E2001 문구만 플랫폼에
//  맞춰 "Android 버전 미달"로 바꿨다).
//

/** 로그 레벨 — 서버가 ERROR·WARN·INFO 세 단계로 정규화한다. */
public enum class SdkLogLevel(public val wire: String) {
    ERROR("ERROR"),
    WARN("WARN"),
    INFO("INFO"),
}

/** 코드·등급·문구를 갖는 로그 식별자의 공통 인터페이스 — [SdkErrorCode]·[SdkInfoCode] 가 구현한다. */
public interface SdkCode {
    /** 콘솔 코드집·트러블슈팅 문서가 가리키는 고정 식별자(예: "E2003"). 언어가 바뀌어도 안 바뀐다. */
    public val code: String

    /** 알람 세기 — 계열(E/I)과는 별개 축. */
    public val level: SdkLogLevel

    /** 사람이 읽는 한 줄 설명 — `onDebugLog` 에만 쓴다(서버로는 코드만 간다). */
    public val summary: String
}

/**
 * SDK 가 남기는 에러의 식별 코드 — 26개.
 *
 * ⚠️ E1005·E1006·E3005 는 결번이다(각각 0.1.17 까지의 키 폴백 코드, v0.1.15 이전 "도면
 * 없는 층" 코드) — 과거 로그의 의미가 바뀌면 안 되므로 번호를 재사용하지 않는다.
 */
public enum class SdkErrorCode(
    override val code: String,
    override val level: SdkLogLevel,
    override val summary: String,
) : SdkCode {

    // 1xxx — 초기화·인증

    /** initialize 없이 다른 API 를 호출했다. */
    NOT_INITIALIZED("E1001", SdkLogLevel.ERROR, "SDK 가 초기화되지 않음"),

    /** SDK 키가 무효하거나 폐기됐다 (401). 재시도해도 소용없다. */
    INVALID_KEY("E1002", SdkLogLevel.ERROR, "SDK 키 무효 또는 폐기"),

    /** 키는 유효하나 테넌트에서 측위가 꺼져 있다. */
    POSITIONING_DISABLED("E1003", SdkLogLevel.WARN, "테넌트에서 측위 비활성"),

    /** identify(profileId:) 없이 측위를 시작하려 했다. */
    NOT_IDENTIFIED("E1004", SdkLogLevel.ERROR, "프로필 미연결"),

    /**
     * 측위 키를 못 구했다 — 콘솔에 없거나 조회에 실패했다. buildings()/floors()/zones() 는
     * 빈 배열로, floor()/locators()/setFloorMap() 은 NOT_INITIALIZED 로 떨어진다 —
     * isInitialized 는 true 인 채로. 관리자가 콘솔 로그 분석기에서 반드시 봐야 하는 자리다.
     */
    KEY_UNAVAILABLE("E1007", SdkLogLevel.ERROR, "측위 키를 못 구함"),

    // 2xxx — 기기·권한

    /** Android 최소 버전 미달. OS 업데이트로 해결된다. */
    OS_VERSION_TOO_LOW("E2001", SdkLogLevel.WARN, "Android 버전 미달"),

    /** UWB(DL-TDoA) 칩이 없다. */
    DEVICE_NOT_SUPPORTED("E2002", SdkLogLevel.WARN, "UWB 미지원 기기"),

    /** 사용자가 측위 권한을 거부했다. 앱에서 재요청 불가 — 설정 앱으로 안내해야 한다. */
    PERMISSION_DENIED("E2003", SdkLogLevel.WARN, "측위 권한 거부"),

    // 3xxx — 공간·설정

    /**
     * 층이 정해지지 않은 채로 측위를 시작했다. 파이프라인은 돌지만 좌표가 나오지 않는다.
     *
     * ⚠️ WARN 이다(0.1.19~). 엔진이 BLE 로 층을 스스로 찾으므로 층 없이 시작하는 것이
     * 정상 경로다(엔진은 begin 해야 돌고, 돌아야 층을 찾는다).
     */
    FLOOR_NOT_SET("E3001", SdkLogLevel.WARN, "층 미지정"),

    /** 층에 로케이터가 등록되어 있지 않다. */
    LOCATORS_MISSING("E3002", SdkLogLevel.WARN, "층에 로케이터 없음"),

    /** 층에 UWB 세션(networkIdentifier)이 없다. 측위 시작 불가. */
    SESSION_ID_MISSING("E3003", SdkLogLevel.WARN, "층에 UWB 세션 없음"),

    /** 층에 존이 하나도 없다 — 좌표는 쌓이지만 진출입 이벤트가 나오지 않는다. */
    ZONES_EMPTY("E3004", SdkLogLevel.WARN, "층에 존 없음"),

    /**
     * 로케이터 조회 자체가 실패했다(통신·서버·404). 층에 로케이터가 없는 것(E3002)과 다르다 —
     * 그쪽은 "안 깔았다", 이쪽은 "못 받았다" 라서 확인할 곳이 현장이 아니라 연동·네트워크다.
     * ⚠️ 도면·존 표시는 막지 않는다. 지도는 그대로 뜨고 측위만 못 한다.
     */
    LOCATORS_FETCH_FAILED("E3006", SdkLogLevel.ERROR, "로케이터 조회 실패 (지도는 정상)"),

    /**
     * 측위를 켰는데 층을 찾지 못했다. E3001(층 미지정)과 다르다 — 그쪽은 앱이 안 고른
     * 것이고, 이쪽은 골라줄 층을 못 찾은 것이다.
     */
    FLOOR_NOT_DETECTED("E3007", SdkLogLevel.WARN, "층 미탐지 (BLE 로 층을 못 찾음)"),

    /**
     * 엔진이 잡은 층과 콘솔에서 지정한 층이 다르다. 서버로 가는 floor_id 가 엔진 값이라,
     * 어긋난 채로 두면 좌표·존 이벤트가 콘솔이 모르는 층에 쌓인다.
     */
    FLOOR_ID_MISMATCH("E3008", SdkLogLevel.ERROR, "엔진 층과 콘솔 층 불일치"),

    /** 엔진이 준 영역 이름에 대응하는 콘솔 존이 없다 — 그 영역의 진출입이 서버로 가지 않는다. */
    ZONE_MAPPING_FAILED("E3009", SdkLogLevel.WARN, "영역 이름에 맞는 콘솔 존 없음"),

    // 4xxx — 측위

    /** UWB 세션이 무효화됐다 (권한 외 사유). */
    UWB_SESSION_FAILED("E4001", SdkLogLevel.ERROR, "UWB 세션 실패"),

    /**
     * 로케이터 신호는 잡히는데 좌표가 산출되지 않는다 — 등록 좌표와 실제 배치 불일치 의심.
     * ⚠️ 신호가 3대 이상 잡히는데도 좌표가 안 나올 때만 발화한다 — "들리는데 못 푼다" =
     * 등록 좌표와 실제 배치가 어긋난 진짜 고장이라 완화 대상이 아니다.
     */
    NO_POSITION_FIX("E4002", SdkLogLevel.ERROR, "좌표 미산출"),

    /** 등록된 로케이터 중 일부가 수신되지 않는다 — 전원·배치 확인 필요. */
    LOCATOR_NOT_RECEIVED("E4003", SdkLogLevel.WARN, "로케이터 일부 미수신"),

    /** 측위 엔진의 영역 판정이 실패했다. 좌표는 계속 나오고 그 회차 판정만 버려진다. */
    AREA_JUDGE_FAILED("E4004", SdkLogLevel.WARN, "영역 판정 실패"),

    // 5xxx — 전송

    /** 네트워크 실패 (오프라인·타임아웃). */
    NETWORK("E5001", SdkLogLevel.ERROR, "네트워크 실패"),

    /** 서버 5xx. */
    SERVER("E5002", SdkLogLevel.ERROR, "서버 오류"),

    /** 페이로드가 서버 계약과 맞지 않는다 (422). 대개 SDK·서버 버전 불일치. */
    UNPROCESSABLE("E5003", SdkLogLevel.ERROR, "요청 형식 불일치"),

    /** 다른 테넌트의 자원에 접근했다 (403). */
    FORBIDDEN("E5004", SdkLogLevel.ERROR, "권한 없는 자원 접근"),

    /** 응답 JSON 이 예상 형태와 다르다. */
    DECODING("E5005", SdkLogLevel.ERROR, "응답 해석 실패"),

    /** 미전송 좌표가 버려졌다 (인메모리 버퍼 — 앱 종료·복구 불가 실패). */
    PENDING_DROPPED("E5006", SdkLogLevel.WARN, "미전송 좌표 유실"),
}

/**
 * 세션 추적용 정보 코드 — 에러가 아니다. 등급은 항상 [SdkLogLevel.INFO].
 *
 * 이게 있어야 "언제 어느 층에서 세션을 열었는데 좌표가 안 나왔다"를 코드 없이 추적할 수
 * 있다.
 */
public enum class SdkInfoCode(
    override val code: String,
    override val summary: String,
) : SdkCode {
    /** 초기화 완료 */
    INITIALIZED("I1001", "초기화 완료"),

    /** 프로필 연결 */
    IDENTIFIED("I1002", "프로필 연결"),

    /** 층 지정 */
    FLOOR_SET("I3001", "층 지정"),

    /** 층에 도면 없음 — 지도만 배경 없이 그린다(측위는 정상) */
    PLAN_MISSING("I3002", "층에 도면 없음 (측위는 정상)"),

    /** 측위 시작 */
    POSITIONING_ON("I4001", "측위 시작"),

    /** 측위 종료 */
    POSITIONING_OFF("I4002", "측위 종료"),

    /** 전송 주기 적용 (기본값과 다를 때만) */
    RATE_APPLIED("I5001", "전송 주기 적용"),
    ;

    override val level: SdkLogLevel get() = SdkLogLevel.INFO
}
