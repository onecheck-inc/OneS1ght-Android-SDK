package co.onecheck.ones1ght.android.identity

//
//  IdentityStore.kt
//  식별자 규약 (사양서 §4)
//
//  · profile_id : SDK 가 만들지 않는다. 고객사가 identify(profileId) 로 넘긴다.
//  · visitor_id : 방문 1건마다 "v-YYYYMMDD-NNN" (NNN = 그날 방문 카운터, 001부터)
//
//  포팅 원본: IdentityStore.swift. iOS 의 Keychain(anon_profile_id 영속)은 안드로이드에는
//  없다 — SDK 가 기기 단위 익명 ID 를 만들지 않는 규약(§4 주석) 자체가 이식 대상이라 여기는
//  visitor_id 카운터만 남는다. SecureStore 자리는 없고, [KeyValueStore] 하나로 충분하다.
//

import co.onecheck.ones1ght.android.runtime.KeyValueStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 방문 ID 발급기. [store] 는 SharedPreferences 자리(실기기: AndroidKeyValueStore). */
public class IdentityStore private constructor(
    private val store: KeyValueStore,
    private val today: () -> String,
) {
    /**
     * 공개 생성자는 날짜 함수를 받지 않는다 — `() -> String` 은 Java 에서 kotlin.jvm.functions.Function0 로
     * 보여 사양서 §3.0(람다 타입 대신 fun interface) 에 어긋난다. 날짜를 주입하는 생성자는 private 이고
     * (internal 생성자는 JVM 에선 public 이라 Java 에 보인다), 모듈 안(테스트)에서는 [create] 로 만든다.
     */
    public constructor(store: KeyValueStore) : this(store, { SimpleDateFormat("yyyyMMdd", Locale.US).format(Date()) })

    /** 방문 ID 발급 — "v-YYYYMMDD-NNN". 호출할 때마다 그날 카운터 +1, 날짜 바뀌면 001부터. */
    public fun newVisitorId(): String {
        val currentDate = today()

        var seq = store.getInt(KEY_VISITOR_SEQ, 0)
        if (store.getString(KEY_VISITOR_DATE) != currentDate) {
            seq = 0 // 날짜 넘어감 → 카운터 리셋
            store.putString(KEY_VISITOR_DATE, currentDate)
        }
        seq += 1
        store.putInt(KEY_VISITOR_SEQ, seq)
        return String.format(Locale.US, "v-%s-%03d", currentDate, seq)
    }

    public companion object {
        private const val KEY_VISITOR_DATE = "onesight.visitor.date"
        private const val KEY_VISITOR_SEQ = "onesight.visitor.seq"

        /** 날짜 함수를 주입하는 모듈 내부 팩토리 — @JvmSynthetic 이라 Java 에는 안 보인다. */
        @JvmSynthetic
        internal fun create(store: KeyValueStore, today: () -> String): IdentityStore = IdentityStore(store, today)
    }
}
