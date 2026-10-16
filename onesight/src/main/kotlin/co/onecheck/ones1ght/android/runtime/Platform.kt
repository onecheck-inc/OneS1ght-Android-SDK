package co.onecheck.ones1ght.android.runtime

//
//  Platform.kt
//  플랫폼 추상화 — 영속 저장소 · 앱 생명주기.
//
//  안드로이드 API(SharedPreferences, ProcessLifecycleOwner)를 코어 코드에서 직접 쓰지 않도록
//  인터페이스 뒤로 숨긴다. 실기기 구현은 AndroidPlatform.kt. 테스트는 [InMemoryKeyValueStore]
//  를 주입해 JVM 에서 그대로 돈다.
//

/**
 * 영속 키·값 저장 계약 (SharedPreferences 자리). **SDK 내부 전용이다**(감사 SF-C1 · SP-C6 · iOS K4 —
 * 0.0.6 까지 공개였다). 공개 API 어디에서도 이 타입을 받지 않는다.
 */
internal interface KeyValueStore {
    public fun getString(key: String): String?
    public fun putString(key: String, value: String?)
    public fun getInt(key: String, default: Int): Int
    public fun putInt(key: String, value: Int)
}

/** 인메모리 구현 — SDK 테스트용(감사 SP-C6 — 0.0.6 까지 배포 AAR 에 공개돼 있었다). */
internal class InMemoryKeyValueStore : KeyValueStore {
    private val strings = mutableMapOf<String, String?>()
    private val ints = mutableMapOf<String, Int>()

    override fun getString(key: String): String? = strings[key]

    override fun putString(key: String, value: String?) {
        strings[key] = value
    }

    override fun getInt(key: String, default: Int): Int = ints[key] ?: default

    override fun putInt(key: String, value: Int) {
        ints[key] = value
    }
}

/** 앱 포그라운드/백그라운드 전환 관찰 계약 (ProcessLifecycleOwner 자리). */
internal interface AppLifecycle {
    fun observe(onBackground: () -> Unit, onForeground: () -> Unit)
    fun stopObserving()

    /**
     * 지금 앱이 화면에 떠 있는가(ProcessLifecycleOwner 가 STARTED 이상) — 통지가 아니라 **지금 값**이다.
     *
     * ⚠️ 2026-10-16: 코디네이터는 이 값을 몰라 「관찰 전엔 떠 있다」 고만 봤다. 그래서 서비스·리시버·WorkManager 처럼
     *    화면 없이 깨어난 프로세스에서 begin() 하면 엔진이 배경에서 켜졌다(iOS #67 과 같은 구멍). 붙이는 순간의
     *    catch-up 통지는 일부러 버리므로([AndroidAppLifecycle]) 지금 상태는 여기서만 읽을 수 있다.
     *    기본값 true — 예전 동작(테스트의 가짜 생명주기 포함)을 그대로 둔다.
     */
    val isInForeground: Boolean get() = true
}
