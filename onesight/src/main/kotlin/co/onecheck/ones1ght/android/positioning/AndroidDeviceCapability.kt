package co.onecheck.ones1ght.android.positioning

//
//  AndroidDeviceCapability.kt
//  [DeviceCapability] 의 실기기 구현 — OS 버전과 측위 엔진의 UWB 하드웨어 판정.
//
//  · 판정은 엔진이 공개한 그대로다: API 37 이상 && UWB 칩 있음(시스템 기능 조회 — 동기, 기다리지 않는다).
//  · 칩은 있어도 DL-TDoA 를 못 하는 기기가 있다 — 그건 엔진이 start 때 능력 콜백으로 확인하고
//    오류 12 로 알린다(E2002 로 매핑). 여기서 미리 물을 방법은 동기로는 없다.
//

import android.content.Context
import android.os.Build

internal class AndroidDeviceCapability(
    /** 지금 아는 applicationContext — initialize·permissions 가 채운다. */
    private val contextProvider: () -> Context?,
    /** 엔진 판정 — 기본은 내장 엔진. */
    private val hardware: (Context) -> Boolean = { IntelligenceHubEngine(it).hardwareAvailable },
) : DeviceCapability {

    override val sdkInt: Int
        get() = Build.VERSION.SDK_INT

    override fun hasUwbHardware(): Boolean {
        if (sdkInt < MIN_ENGINE_SDK) return false
        val context = contextProvider() ?: return false
        return runCatching { hardware(context) }.getOrDefault(false)
    }

    private companion object {
        const val MIN_ENGINE_SDK = 37
    }
}
