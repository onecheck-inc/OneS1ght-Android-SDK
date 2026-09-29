package co.onecheck.ones1ght.android.positioning

//
//  DeviceCapability.kt
//  기기가 측위를 할 수 있는가 — 계약만(android.* 미참조). 실기기 구현은 [AndroidDeviceCapability],
//  JVM 단위테스트는 가짜로 갈아 끼운다.
//

/** 기기의 측위 가능 여부를 묻는 계약. */
internal interface DeviceCapability {
    /** `android.os.Build.VERSION.SDK_INT` 에 대응 — 버전별 분기를 이 값 하나로 테스트한다. */
    val sdkInt: Int

    /**
     * 이 기기에 UWB 칩이 있는가 — 동기·즉답(시스템 기능 조회). Context 를 아직 모르면 false.
     * 정확한 DL-TDoA 지원 여부는 측위 엔진이 시작할 때 다시 확인한다(미지원이면 E2002 로 남는다).
     */
    fun hasUwbHardware(): Boolean
}
