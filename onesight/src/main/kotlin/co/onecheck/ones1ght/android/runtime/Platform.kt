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
 * 영속 키·값 저장 계약 (SharedPreferences 자리). **SDK 내부 전용이다** — 0.2 에서 internal 로 바뀐다(감사 SF-C1 ·
 * SP-C6). 공개 API 어디에서도 이 타입을 받지 않으므로 앱이 구현할 일이 없다.
 */
@Deprecated("0.2 에서 internal 로 바뀜", level = DeprecationLevel.WARNING)
public interface KeyValueStore {
    public fun getString(key: String): String?
    public fun putString(key: String, value: String?)
    public fun getInt(key: String, default: Int): Int
    public fun putInt(key: String, value: Int)
}

/** 인메모리 구현 — SDK 테스트용. 0.2 에서 internal 로 바뀐다(감사 SP-C6 — 테스트용 타입이 배포 AAR 에 공개돼 있었다). */
@Deprecated("0.2 에서 internal 로 바뀜", level = DeprecationLevel.WARNING)
@Suppress("DEPRECATION") // 같은 0.2 정리 대상끼리
public class InMemoryKeyValueStore : KeyValueStore {
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
}
