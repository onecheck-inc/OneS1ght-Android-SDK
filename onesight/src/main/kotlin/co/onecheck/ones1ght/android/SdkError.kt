package co.onecheck.ones1ght.android

//
//  SdkError.kt
//  서버 에러(ApiError) 밖의 SDK 수준 실패.
//
//  포팅 원본: SessionCoordinator.swift 의 `SdkError`. Ruling 2 — 하위 타입은 `object` 가 아니라
//  `class` 다(Java 에서 `new`·`instanceof` 가 자연스럽고 스택 트레이스를 공유하지 않는다).
//  같음은 타입으로만 본다(iOS `Equatable` enum 과 같은 의미).
//  message 는 Java 의 getMessage() 로 고객에게 그대로 닿는 공개 문자열이라 영어 + 코드로 둔다
//  (로그 문구의 다국어는 SdkLocalized 의 몫이다).
//

import co.onecheck.ones1ght.android.runtime.SdkErrorCode

public sealed class SdkError(message: String) : Exception(message) {

    /** 이 실패에 대응하는 로그 코드 — 콘솔 코드집과 같은 식별자. */
    public abstract val code: SdkErrorCode

    /** initialize() 안 하고 begin() 등을 호출했다. */
    public class NotInitialized : SdkError("SDK not initialized (E1001)") {
        override val code: SdkErrorCode get() = SdkErrorCode.NOT_INITIALIZED
    }

    /** identify(profileId) 없이 begin() 을 호출했다. */
    public class NotIdentified : SdkError("Profile not identified (E1004)") {
        override val code: SdkErrorCode get() = SdkErrorCode.NOT_IDENTIFIED
    }

    /** verify 는 통과했으나 positioning_enabled=false. */
    public class PositioningDisabled : SdkError("Positioning disabled for this tenant (E1003)") {
        override val code: SdkErrorCode get() = SdkErrorCode.POSITIONING_DISABLED
    }

    /** UWB(DL-TDoA) 미지원 기기. */
    public class DeviceNotSupported : SdkError("Device does not support UWB positioning (E2002)") {
        override val code: SdkErrorCode get() = SdkErrorCode.DEVICE_NOT_SUPPORTED
    }

    /** Android 최소 버전 미달. */
    public class OsVersionTooLow : SdkError("Android version too low for positioning (E2001)") {
        override val code: SdkErrorCode get() = SdkErrorCode.OS_VERSION_TOO_LOW
    }

    /**
     * 층을 지정하려는데 건물을 알 수 없다 — `setFloorMap(floor)` 를 건물 인자 없이, 직전에 지정한 건물도
     * 없이 불렀다. 처음 지정할 때는 `setFloorMap(floor, buildingId)` 로 건물을 함께 넘긴다(그 뒤로는 생략 가능).
     * 층 상태는 바뀌지 않는다. 코드는 E3001(층 미지정).
     */
    public class BuildingNotSet : SdkError("Building not set — call setFloorMap(floor, buildingId) first (E3001)") {
        override val code: SdkErrorCode get() = SdkErrorCode.FLOOR_NOT_SET
    }

    /** 같은 하위 타입이면 같다 — 담은 값이 없어 타입이 곧 정체다. */
    override fun equals(other: Any?): Boolean = other != null && other.javaClass == javaClass

    override fun hashCode(): Int = javaClass.hashCode()
}
