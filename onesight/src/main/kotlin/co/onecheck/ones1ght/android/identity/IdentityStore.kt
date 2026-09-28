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
internal class IdentityStore(
    private val store: KeyValueStore,
    private val today: () -> String = { SimpleDateFormat("yyyyMMdd", Locale.US).format(Date()) },
) {

    /** 방문 ID 발급 — "v-YYYYMMDD-NNN". 호출할 때마다 그날 카운터 +1, 날짜 바뀌면 001부터. */
    fun newVisitorId(): String {
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

    private companion object {
        const val KEY_VISITOR_DATE = "onesight.visitor.date"
        const val KEY_VISITOR_SEQ = "onesight.visitor.seq"
    }
}
