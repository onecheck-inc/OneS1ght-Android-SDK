package co.onecheck.ones1ght.android.positioning

import co.onecheck.ones1ght.android.identity.IdentityStore
import co.onecheck.ones1ght.android.model.SdkDefaults
import co.onecheck.ones1ght.android.network.ApiClient
import co.onecheck.ones1ght.android.runtime.InMemoryKeyValueStore
import co.onecheck.ones1ght.android.runtime.KeyValueStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.2 에서 internal 로 바뀔 공개 타입에 경고(@Deprecated WARNING)가 붙어 있는가 — 감사 SF-C1·SP-C6 · iOS K4.
 * 지금(0.0.x)은 깨지 않는다: 경고만 내고 그대로 동작해야 한다(Migrations/android.json planned[] 에 목록).
 */
@Suppress("DEPRECATION")
class DeprecatedSurfaceTest {

    private fun deprecated(a: Array<Annotation>): Deprecated? = a.filterIsInstance<Deprecated>().firstOrNull()

    @Test fun internalBoundTypesWarn() {
        for (c in listOf(IdentityStore::class.java, KeyValueStore::class.java, InMemoryKeyValueStore::class.java,
            SdkDefaults::class.java, MockPositioningProvider::class.java)) {
            val d = deprecated(c.annotations)
            assertTrue("${c.simpleName} 에 @Deprecated 가 없다", d != null)
            assertEquals("0.2 에서 internal 로 바뀜", d!!.message)
            assertEquals(DeprecationLevel.WARNING, d.level)
        }
        // ApiClient 는 타입을 남기고(DEFAULT_BASE_URL) 생성자·키·주소에만 단다(Java 는 클래스 파일의 Deprecated 속성으로 경고).
        val ctor = ApiClient::class.java.constructors.first { it.parameterCount == 2 }
        assertEquals("0.2 에서 internal 로 바뀜", deprecated(ctor.annotations)?.message)
    }

    /** 경고만 — 지금은 그대로 동작한다. */
    @Test fun stillWorks() {
        val api = ApiClient("k", "https://example.invalid/api")
        assertEquals("k", api.apiKey)
        assertEquals("https://example.invalid/api", api.baseUrl)
        assertEquals(4, SdkDefaults.POSITION_RATE_HZ)
        assertEquals(1, SdkDefaults.MIN_RATE_HZ)
        assertEquals(100, SdkDefaults.MAX_RATE_HZ)
        val store: KeyValueStore = InMemoryKeyValueStore()
        assertEquals(true, IdentityStore(store).newVisitorId().startsWith("v-"))
        val d = UwbPositioningProvider.AnchorDiagnostic(listOf(1), listOf(1), listOf(1), emptyList(), true, "s")
        assertEquals(d.received, d.matched)
        assertEquals(d.hasFix, d.canPosition)
    }

    /** SP-C9 — 구역만 바꾸는 새 메서드의 기본 구현은 예전 호출(apply(PositioningConfig(zones)))과 같다. */
    @Test fun applyZonesDefaultDelegatesToApply() {
        val mock = MockPositioningProvider()
        val zones = listOf(co.onecheck.ones1ght.android.model.Zone("z", "Z", emptyList()))
        mock.applyZones(zones)
        assertEquals(co.onecheck.ones1ght.android.positioning.PositioningConfig(zones = zones), mock.appliedConfig)
    }
}
