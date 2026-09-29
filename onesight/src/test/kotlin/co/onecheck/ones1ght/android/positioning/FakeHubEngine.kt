package co.onecheck.ones1ght.android.positioning

/**
 * 가짜 측위 엔진 — 호출을 기록하고, 테스트가 [current] 로 엔진 콜백을 직접 쏜다.
 * 실제 엔진처럼 콜백은 "엔진 스레드" 에서 오는 것으로 치고, provider 가 코어 디스패처로 넘긴다.
 */
internal class FakeHubEngine(
    override var version: String = "9.9.9",
    override var hardwareAvailable: Boolean = true,
) : HubEngine {
    val licenses = mutableListOf<String>()
    var starts = 0
    var stops = 0
    /** 지금 등록된 리스너 — 테스트가 이걸로 엔진 콜백을 쏜다. */
    var current: HubEngine.Listener? = null
        private set

    /** 지금까지 등록된 리스너(해제 null 포함) — 세대가 바뀌는지 본다. */
    val listenerHistory = mutableListOf<HubEngine.Listener?>()
    var throwOnStart: RuntimeException? = null

    override fun setLicense(key: String) {
        licenses += key
    }

    override fun setListener(listener: HubEngine.Listener?) {
        current = listener
        listenerHistory += listener
    }

    override fun start() {
        throwOnStart?.let { throw it }
        starts += 1
    }

    override fun stop() {
        stops += 1
    }
}
