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

/** [ProcessLifecycleOwner] 의 ON_STOP/ON_START 를 [AppLifecycle] 로 옮긴다. */
internal class AndroidAppLifecycle : AppLifecycle {

    private var observer: DefaultLifecycleObserver? = null

    override fun observe(onBackground: () -> Unit, onForeground: () -> Unit) {
        stopObserving()
        val newObserver = object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) = onBackground()
            override fun onStart(owner: LifecycleOwner) = onForeground()
        }
        observer = newObserver
        ProcessLifecycleOwner.get().lifecycle.addObserver(newObserver)
    }

    override fun stopObserving() {
        observer?.let { ProcessLifecycleOwner.get().lifecycle.removeObserver(it) }
        observer = null
    }
}
