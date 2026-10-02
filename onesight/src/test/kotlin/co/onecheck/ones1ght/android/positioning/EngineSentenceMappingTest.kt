package co.onecheck.ones1ght.android.positioning

import co.onecheck.ones1ght.android.runtime.SdkErrorCode
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 감사 SP-B9 — 엔진 오류를 **엔진 1.1.0 이 실제로 만드는 문장 그대로** 넣어 E-코드를 고정한다. 엔진을 올렸을 때
 * 문장이 바뀌면 여기서 깨져야 한다(오류 3 은 문장으로만 꺼짐·권한·미지원이 갈린다).
 *
 * 새 E-코드는 만들지 않는다(공개 enum 확장은 고객 `when` 을 깨뜨릴 수 있다). 기존 코드 안에서 할 일이 맞는
 * 곳으로만 옮겼다:
 *  · 1 라이선스 미설정 · 10 라이선스 거부 → **E1007**(측위 키 문제 — 콘솔의 측위 키를 본다). 예전엔 E1002
 *    「SDK 키 무효」라 멀쩡한 SDK 키(ock_)를 의심하게 했다.
 *  · 3 + `unsupported on this device` → **E2002**(미지원 기기). 예전엔 E2003 「권한 거부」.
 *  · 7 위치(권한·정밀도·서비스 꺼짐) · 9 매니페스트 RANGING 선언 누락 → E2003 그대로 — 맞는 기존 코드가 없다
 *    (문맥 `engine=7 …`·`engine=9 …` 로 갈린다).
 */
class EngineSentenceMappingTest {

    private fun map(code: Int, message: String) = UwbPositioningProvider.sdkCode(code, message)

    @Test fun bluetoothSentences() {
        assertEquals(SdkErrorCode.BLUETOOTH_OFF, map(3, "bluetooth unavailable: powered off"))
        assertEquals(
            SdkErrorCode.PERMISSION_DENIED,
            map(3, "bluetooth unavailable: permission required — app must request BLUETOOTH_SCAN and wait for user response"),
        )
        assertEquals(SdkErrorCode.DEVICE_NOT_SUPPORTED, map(3, "bluetooth unavailable: unsupported on this device"))
    }

    @Test fun locationSentencesStayPermission() {
        assertEquals(SdkErrorCode.PERMISSION_DENIED, map(7, "location unavailable: location services are disabled system-wide"))
        assertEquals(
            SdkErrorCode.PERMISSION_DENIED,
            map(7, "location unavailable: permission required — app must request ACCESS_FINE_LOCATION and wait for user response"),
        )
        assertEquals(
            SdkErrorCode.PERMISSION_DENIED,
            map(7, "location unavailable: only coarse location granted — fine location required for BLE scan filters"),
        )
    }

    @Test fun manifestDeclarationMissingStaysPermission() {
        assertEquals(
            SdkErrorCode.PERMISSION_DENIED,
            map(9, "configuration missing: AndroidManifest.xml <uses-permission android:name=\"android.permission.RANGING\"/> missing"),
        )
    }

    @Test fun engineLicenseIsPositioningKeyNotSdkKey() {
        assertEquals(SdkErrorCode.KEY_UNAVAILABLE, map(1, "license is not set — call IntelligenceHub.setLicense(String) first"))
        assertEquals(SdkErrorCode.KEY_UNAVAILABLE, map(10, "license is invalid"))
    }

    @Test fun otherSentences() {
        assertEquals(SdkErrorCode.NETWORK, map(11, "server unavailable"))
        assertEquals(SdkErrorCode.DEVICE_NOT_SUPPORTED, map(12, "dl-tdoa is not supported on this device: os version below required minimum (37)"))
        assertEquals(SdkErrorCode.DEVICE_NOT_SUPPORTED, map(12, "dl-tdoa is not supported on this device: device capability check reports unsupported"))
        assertEquals(
            SdkErrorCode.FLOOR_NOT_DETECTED,
            map(13, "scan started too frequently — wait and retry (Android BLE scan-start throttle)"),
        )
    }
}
