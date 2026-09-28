package co.onecheck.ones1ght.android.runtime

//
//  AndroidPlatform.kt
//  [KeyValueStore]·[AppLifecycle] 의 실기기 구현. android.* 를 쓸 수 있는 몇 안 되는 파일 중
//  하나다(전역 제약 참고) — 그래서 여기는 얇게 두고, 로직은 전부 코어(IdentityStore 등)에 둔다.
//

import android.content.Context
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner

/** SharedPreferences 기반 [KeyValueStore]. 파일명은 SDK 패키지명 그대로. */
internal class AndroidKeyValueStore(context: Context) : KeyValueStore {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun getString(key: String): String? = prefs.getString(key, null)

    override fun putString(key: String, value: String?) {
        prefs.edit().putString(key, value).apply()
    }

    override fun getInt(key: String, default: Int): Int = prefs.getInt(key, default)

    override fun putInt(key: String, value: Int) {
        prefs.edit().putInt(key, value).apply()
    }

    private companion object {
        const val PREFS_NAME = "co.onecheck.ones1ght.android"
    }
}

/**
 * [ProcessLifecycleOwner] 의 ON_STOP/ON_START 를 [AppLifecycle] 로 옮긴다.
 *
 * ⚠️ 이미 STARTED 인 프로세스에 관찰자를 붙이면 addObserver 가 그 안에서 ON_CREATE·ON_START 를
 * 곧바로 다시 준다(catch-up). 그건 "전경으로 돌아왔다"가 아니라 "지금 전경이다"라는 뜻이라
 * 넘기지 않는다 — 넘기면 코디네이터가 방금 붙인 스트림을 하나 더 연다.
 */
internal class AndroidAppLifecycle : AppLifecycle {

    private var observer: DefaultLifecycleObserver? = null

    /** addObserver 호출 중인가 — 그동안 동기로 들어오는 catch-up 통지를 버린다. */
    private var adding = false

    override fun observe(onBackground: () -> Unit, onForeground: () -> Unit) {
        stopObserving()
        val newObserver = object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) {
                if (!adding) onBackground()
            }

            override fun onStart(owner: LifecycleOwner) {
                if (!adding) onForeground()
            }
        }
        observer = newObserver
        adding = true
        try {
            ProcessLifecycleOwner.get().lifecycle.addObserver(newObserver)
        } finally {
            adding = false
        }
    }

    override fun stopObserving() {
        observer?.let { ProcessLifecycleOwner.get().lifecycle.removeObserver(it) }
        observer = null
    }
}
