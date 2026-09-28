package co.onecheck.ones1ght.android

//
//  SdkError.kt
//  서버 에러(ApiError) 밖의 SDK 수준 실패.
//
//  포팅 원본: SessionCoordinator.swift 의 `SdkError`. Ruling 2 — 하위 타입은 `object` 가 아니라
//  `class` 다(Java 에서 `new`·`instanceof` 가 자연스럽고 스택 트레이스를 공유하지 않는다).
//  같음은 타입으로만 본다(iOS `Equatable` enum 과 같은 의미).
//

import co.onecheck.ones1ght.android.runtime.SdkErrorCode

public sealed class SdkError(message: String) : Exception(message) {

    /** 이 실패에 대응하는 로그 코드 — 콘솔 코드집과 같은 식별자. */
    public abstract val code: SdkErrorCode

    /** initialize() 안 하고 begin() 등을 호출했다. */
    public class NotInitialized : SdkError("SDK 가 초기화되지 않음") {
        override val code: SdkErrorCode get() = SdkErrorCode.NOT_INITIALIZED
    }

    /** identify(profileId) 없이 begin() 을 호출했다. */
    public class NotIdentified : SdkError("프로필 미연결") {
        override val code: SdkErrorCode get() = SdkErrorCode.NOT_IDENTIFIED
    }

    /** verify 는 통과했으나 positioning_enabled=false. */
    public class PositioningDisabled : SdkError("테넌트에서 측위 비활성") {
        override val code: SdkErrorCode get() = SdkErrorCode.POSITIONING_DISABLED
    }

    /** UWB(DL-TDoA) 미지원 기기. */
    public class DeviceNotSupported : SdkError("UWB 미지원 기기") {
        override val code: SdkErrorCode get() = SdkErrorCode.DEVICE_NOT_SUPPORTED
    }

    /** Android 최소 버전 미달. */
    public class OsVersionTooLow : SdkError("Android 버전 미달") {
        override val code: SdkErrorCode get() = SdkErrorCode.OS_VERSION_TOO_LOW
    }

    /** 같은 하위 타입이면 같다 — 담은 값이 없어 타입이 곧 정체다. */
    override fun equals(other: Any?): Boolean = other != null && other.javaClass == javaClass

    override fun hashCode(): Int = javaClass.hashCode()
}
