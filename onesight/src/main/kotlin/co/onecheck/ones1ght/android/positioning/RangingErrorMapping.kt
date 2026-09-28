package co.onecheck.ones1ght.android.positioning

//
//  RangingErrorMapping.kt
//  레인징 사유(android.ranging.RangingSession.Callback 의 reason) → SDK E-코드.
//
//  이 매핑이 있어야 엔진 고유의 실패가 콘솔 로그 분석기까지 간다. 화면 로그로만 남기면
//  현장에서만 보이고 관리자는 "안 됐다"는 말만 듣는다.
//
//  ⚠️ reason 상수는 android.ranging 값을 그대로 참조하지 않고 같은 정수를 여기 복제한다 —
//     코어 로직(이 파일)은 JVM 단위테스트에서 도는데, android.* 클래스는 로보렉트릭 없이는
//     JVM 에서 로드되지 않는다. 값은 `javap -constants -cp <android.jar>
//     'android.ranging.RangingSession$Callback'` 로 확인했다(android-37.0).
//
//  REASON_LOCAL_REQUEST(우리가 직접 stop 요청) 는 정상 종료라 코드를 올리지 않는다.
//

import co.onecheck.ones1ght.android.runtime.SdkErrorCode

internal object RangingErrorMapping {

    internal const val REASON_UNKNOWN: Int = 0
    internal const val REASON_LOCAL_REQUEST: Int = 1
    internal const val REASON_REMOTE_REQUEST: Int = 2
    internal const val REASON_UNSUPPORTED: Int = 3
    internal const val REASON_SYSTEM_POLICY: Int = 4
    internal const val REASON_NO_PEERS_FOUND: Int = 5

    /**
     * [reason] → SDK 에러 코드. `null` 이면 올릴 코드가 없다(정상 종료거나, 모르는 사유를
     * 함부로 짐작하지 않는 것).
     *
     * [security] 가 서면(보안 예외로 세션이 닫힌 경우) 사유 값과 무관하게 항상 권한 거부다.
     */
    internal fun code(reason: Int, security: Boolean): SdkErrorCode? {
        if (security) return SdkErrorCode.PERMISSION_DENIED
        return when (reason) {
            REASON_UNSUPPORTED -> SdkErrorCode.DEVICE_NOT_SUPPORTED
            REASON_SYSTEM_POLICY -> SdkErrorCode.PERMISSION_DENIED
            REASON_NO_PEERS_FOUND -> SdkErrorCode.NO_POSITION_FIX
            REASON_UNKNOWN, REASON_REMOTE_REQUEST -> SdkErrorCode.UWB_SESSION_FAILED
            REASON_LOCAL_REQUEST -> null
            else -> null // 모르는(미래) 사유를 아무 코드에나 붙이지 않는다.
        }
    }
}
