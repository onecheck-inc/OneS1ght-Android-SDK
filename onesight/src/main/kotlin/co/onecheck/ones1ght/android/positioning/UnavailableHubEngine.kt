package co.onecheck.ones1ght.android.positioning

//
//  UnavailableHubEngine.kt
//  측위 엔진이 없는 OS(API 37 미만)에서 [UwbPositioningProvider] 를 만들 때 끼우는 자리표시자.
//
//  공개 생성자 UwbPositioningProvider(context) 는 어느 OS 에서든 던지지 않고 만들어져야 한다 — 패키지는
//  API 26 부터 설치되고, 앱은 화면을 열 때 provider 를 먼저 들고 나서 isSupported 로 분기한다(iOS 와 같은 모양).
//  그런데 실제 엔진은 API 37 의 android.ranging 을 써서 그 아래에서는 클래스를 올리는 순간 터진다. 그래서
//  여기서는 엔진 클래스를 전혀 건드리지 않고, 시작하면 엔진이 미지원 기기에 주는 것과 같은 오류(12)를 준다 —
//  provider 는 평소 경로 그대로 E2002 를 남기고 IDLE 로 돌아간다.
//

internal class UnavailableHubEngine : HubEngine {

    private var listener: HubEngine.Listener? = null

    override val version: String get() = "-"

    override val hardwareAvailable: Boolean get() = false

    override fun setLicense(key: String) {}

    override fun setListener(listener: HubEngine.Listener?) {
        this.listener = listener
    }

    override fun start() {
        listener?.onError(UNSUPPORTED_DEVICE, "positioning requires Android 17 (API 37) or later")
    }

    override fun stop() {
        listener?.onStopped()
    }

    private companion object {
        /** 엔진 오류 12 — 미지원 기기(OS 미달 포함). */
        const val UNSUPPORTED_DEVICE = 12
    }
}
