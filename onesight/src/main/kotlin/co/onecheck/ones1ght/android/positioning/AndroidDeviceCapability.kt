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
import co.onecheck.ones1ght.android.OneS1ght
import co.onecheck.ones1ght.android.runtime.LogLevel

internal class AndroidDeviceCapability(
    /** 지금 아는 applicationContext — initialize·permissions 가 채운다. */
    private val contextProvider: () -> Context?,
    /** 엔진 판정 — 기본은 내장 엔진. API 37 미만이면 엔진 클래스를 건드리지 않고 false. */
    private val hardware: (Context) -> Boolean = ::engineHardwareAvailable,
    private val sdkIntProvider: () -> Int = { Build.VERSION.SDK_INT },
    /** 엔진 클래스 링크 실패를 알리는 곳 — 기본은 `OneS1ght.onDebugLog`(ERROR). */
    private val onLinkageError: (LogLevel, String) -> Unit = { level, msg -> OneS1ght.onDebugLog?.onLog(level, msg) },
) : DeviceCapability {

    override val sdkInt: Int
        get() = sdkIntProvider()

    /**
     * 판정은 던지지 않는다(false). 단 엔진 클래스가 없거나 링크가 깨진 것(LinkageError — R8/ProGuard 가 엔진을
     * 지운 경우 등)은 「미지원 기기」로 위장하지 않고 ERROR 로 드러낸다 — 기기 문제가 아니라 앱 빌드 문제다
     * (감사 SP-B13: 예전엔 Throwable 을 통째로 삼켜 E2002 로만 보였다). 일반 예외는 종전대로 조용히 false.
     */
    override fun hasUwbHardware(): Boolean {
        if (sdkInt < MIN_POSITIONING_SDK) return false
        val context = contextProvider() ?: return false
        return try {
            hardware(context)
        } catch (e: LinkageError) {
            onLinkageError(
                LogLevel.ERROR,
                "positioning engine classes unavailable (${e.javaClass.simpleName}: ${e.message}) — " +
                    "check that R8/ProGuard keeps the SDK's engine classes; this is an app build issue, not the device",
            )
            false
        } catch (e: Exception) {
            false
        }
    }
}

/**
 * 내장 엔진의 UWB 칩 판정. 게이트는 여기서 직접 `Build.VERSION.SDK_INT` 로 본다 — lint 가 확인할 수 있고,
 * API 37 미만 기기에서는 엔진 클래스를 로드하지 않는다(엔진은 android.ranging 을 쓴다).
 */
private fun engineHardwareAvailable(context: Context): Boolean =
    if (Build.VERSION.SDK_INT >= MIN_POSITIONING_SDK) IntelligenceHubEngine(context).hardwareAvailable else false
