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
import co.onecheck.ones1ght.android.MIN_POSITIONING_SDK

internal class AndroidDeviceCapability(
    /** 지금 아는 applicationContext — initialize·permissions 가 채운다. */
    private val contextProvider: () -> Context?,
    /** 엔진 판정 — 기본은 내장 엔진. API 37 미만이면 엔진 클래스를 건드리지 않고 false. */
    private val hardware: (Context) -> Boolean = ::engineHardwareAvailable,
) : DeviceCapability {

    override val sdkInt: Int
        get() = Build.VERSION.SDK_INT

    override fun hasUwbHardware(): Boolean {
        if (sdkInt < MIN_POSITIONING_SDK) return false
        val context = contextProvider() ?: return false
        return runCatching { hardware(context) }.getOrDefault(false)
    }
}

/**
 * 내장 엔진의 UWB 칩 판정. 게이트는 여기서 직접 `Build.VERSION.SDK_INT` 로 본다 — lint 가 확인할 수 있고,
 * API 37 미만 기기에서는 엔진 클래스를 로드하지 않는다(엔진은 android.ranging 을 쓴다).
 */
private fun engineHardwareAvailable(context: Context): Boolean =
    if (Build.VERSION.SDK_INT >= MIN_POSITIONING_SDK) IntelligenceHubEngine(context).hardwareAvailable else false
