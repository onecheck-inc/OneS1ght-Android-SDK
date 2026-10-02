package co.onecheck.ones1ght.android.runtime

//
//  SdkConstants.kt
//  여러 파일이 같은 값을 써야 하는 상수 — 한 곳만 바뀌어 어긋나지 않게 모아 둔다(감사 SF-C5 · iOS K15).
//
//  포팅 원본: SdkConstants.swift(iOS #55). 이름도 같다.
//

/** 서버 계약이 정한 상한. */
internal object SdkLimits {
    /** 좌표·로그 요청 하나에 실을 수 있는 최대 건수 — 서버가 넘으면 422 를 준다(사양서 §6.5). */
    const val MAX_PER_REQUEST: Int = 500
}

/** 서버로 보내는 플랫폼 이름 — verify·좌표·존 이벤트·로그가 같은 값을 써야 콘솔이 한 기기로 묶는다. */
internal object SdkPlatform {
    const val NAME: String = "Android"
}

/** 요청 타임아웃(초). 연결·읽기·쓰기에 같은 값을 건다. */
internal object SdkTimeouts {
    /** SDK 백엔드(verify·좌표·존 이벤트·프로필·로그) — 응답이 작아 짧게 둔다. */
    const val API_SECONDS: Long = 10

    /** 공간 조회(건물·층·도면·로케이터·구역) — 도면 응답이 수백 KB 라 길게 둔다. */
    const val SPACE_SECONDS: Long = 20
}
