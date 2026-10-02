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

    /**
     * 동기 콜백 모드(감사 SP-C10) — start() **안에서** 등록된 리스너를 곧바로 부른다. 실제 엔진은 시작을 접을 때
     * (라이선스 없음·권한 이미 거부) 같은 호출 안에서 onError 를 줄 수 있고, 운영 디스패처(Main.immediate)는 그
     * 콜백을 곧장 이어서 돌린다. 예전 가짜 엔진은 콜백이 늘 비동기라 이 경로(SP-B3)를 못 밟았다.
     */
    var onStartSync: ((HubEngine.Listener) -> Unit)? = null

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
        val listener = current
        onStartSync?.let { cb -> if (listener != null) cb(listener) }
    }

    override fun stop() {
        stops += 1
    }
}
