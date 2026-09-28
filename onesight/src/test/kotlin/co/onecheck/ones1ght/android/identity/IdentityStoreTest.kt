package co.onecheck.ones1ght.android.identity

import co.onecheck.ones1ght.android.runtime.InMemoryKeyValueStore
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * visitor_id 형식(`v-yyyyMMdd-NNN`)·같은 날 카운터 증가·날짜 바뀌면 001 리셋 검증.
 * 포팅 원본: IdentityStoreTests.swift.
 */
class IdentityStoreTest {

    @Test fun visitorIdFormatAndDailyCounter() {
        val store = IdentityStore.create(InMemoryKeyValueStore(), today = { "20260718" })
        assertEquals("v-20260718-001", store.newVisitorId())
        assertEquals("v-20260718-002", store.newVisitorId())
        assertEquals("v-20260718-003", store.newVisitorId())
    }

    @Test fun visitorIdResetsOnNewDay() {
        var current = "20260718"
        val store = IdentityStore.create(InMemoryKeyValueStore(), today = { current })
        assertEquals("v-20260718-001", store.newVisitorId())
        assertEquals("v-20260718-002", store.newVisitorId())

        current = "20260719" // 자정 넘김
        assertEquals("v-20260719-001", store.newVisitorId())
    }
}
