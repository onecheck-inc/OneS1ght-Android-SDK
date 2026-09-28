package co.onecheck.ones1ght.android.positioning

//
//  DeviceCapability.kt
//  기기가 DL-TDoA 측위를 할 수 있는가 — 실제 조회(android.ranging.RangingManager 등)는
//  `positioning/AndroidDeviceCapability.kt`(다른 태스크)가 구현한다. 여기는 계약만이라
//  android.* 를 참조하지 않고, JVM 단위테스트가 가짜 구현으로 대체할 수 있다.
//

/** 기기의 UWB(DL-TDoA) 지원 여부를 묻는 계약. */
internal interface DeviceCapability {
    /** `android.os.Build.VERSION.SDK_INT` 에 대응 — 버전별 분기를 이 값 하나로 테스트한다. */
    val sdkInt: Int

    /** 이 기기가 DL-TDoA 측위 가능한가(칩 유무 등) — 비동기 조회일 수 있어 suspend. */
    suspend fun supportsDlTdoa(): Boolean
}
