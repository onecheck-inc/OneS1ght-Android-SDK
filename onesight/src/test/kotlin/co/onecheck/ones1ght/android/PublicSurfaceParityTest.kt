package co.onecheck.ones1ght.android

import co.onecheck.ones1ght.android.identity.IdentityStore
import co.onecheck.ones1ght.android.model.ConfigChange
import co.onecheck.ones1ght.android.model.SdkDefaults
import co.onecheck.ones1ght.android.network.ApiClient
import co.onecheck.ones1ght.android.positioning.MockPositioningProvider
import co.onecheck.ones1ght.android.positioning.PositioningProvider
import co.onecheck.ones1ght.android.positioning.UwbPositioningProvider
import co.onecheck.ones1ght.android.runtime.InMemoryKeyValueStore
import co.onecheck.ones1ght.android.runtime.KeyValueStore
import java.lang.reflect.Modifier
import kotlin.metadata.Visibility
import kotlin.metadata.jvm.KotlinClassMetadata
import kotlin.metadata.visibility
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 공개 표면이 iOS SDK main(#55) 과 같은가 — iOS 가 사양이다(2026-10-02 사용자 결정).
 *
 *  · iOS 가 internal 로 내리거나 지운 타입의 안드 대응은 Kotlin internal 이다.
 *  · iOS 가 이름을 바꾼 API 는 새 이름이 있고, 옛 이름은 @Deprecated(WARNING) 로 그대로 동작한다.
 */
class PublicSurfaceParityTest {

    private fun kotlinVisibility(c: Class<*>): Visibility? =
        (c.getAnnotation(Metadata::class.java)?.let { KotlinClassMetadata.readLenient(it) } as? KotlinClassMetadata.Class)
            ?.kmClass?.visibility

    @Test fun internalBoundTypesAreInternal() {
        for (c in listOf(
            IdentityStore::class.java, KeyValueStore::class.java, InMemoryKeyValueStore::class.java,
            SdkDefaults::class.java, MockPositioningProvider::class.java,
        )) {
            assertEquals("${c.simpleName} 는 iOS 처럼 SDK 내부여야 한다", Visibility.INTERNAL, kotlinVisibility(c))
        }
        // ApiClient 는 타입·옛 상수만 남는다(iOS 와 같은 모양) — 공개 생성자가 없다.
        assertTrue("ApiClient 공개 생성자: " + ApiClient::class.java.constructors.toList(), ApiClient::class.java.constructors.none { Modifier.isPublic(it.modifiers) && !it.isSynthetic })
        // ConfigChange 의 빈 companion 도 공개에서 내렸다.
        val companion = ConfigChange::class.java.declaredClasses.firstOrNull { it.simpleName == "Companion" }
        assertTrue("ConfigChange.Companion: ${companion?.let { kotlinVisibility(it) }}", companion == null || kotlinVisibility(companion) == Visibility.INTERNAL)
        // applyZones 는 공개 계약이 아니다(iOS 에 없다) — 내장 provider 의 SDK 내부 길.
        assertFalse(PositioningProvider::class.java.methods.any { it.name == "applyZones" })
    }

    @Test fun renamedApisKeepDeprecatedOldNames() {
        fun deprecatedMessage(name: String, vararg params: Class<*>): String? =
            OneS1ght::class.java.getMethod(name, *params).getAnnotation(Deprecated::class.java)?.message
        assertEquals("requestPermission 으로 바뀜",
            deprecatedMessage("permissions", androidx.activity.ComponentActivity::class.java, Callback::class.java))
        assertEquals("fetchProfile 로 바뀜", deprecatedMessage("getProfile", String::class.java, Callback::class.java))
        assertEquals("replaceProfile 로 바뀜",
            deprecatedMessage("putProfile", String::class.java, Map::class.java, Callback::class.java))
        assertEquals("uploadPendingPositions 로 바뀜", deprecatedMessage("send", Callback::class.java))
        assertEquals("discardPendingPositions 로 바뀜", deprecatedMessage("empty"))
        // 새 이름은 경고가 없다.
        assertTrue(OneS1ght::class.java.getMethod("discardPendingPositions").getAnnotation(Deprecated::class.java) == null)
    }

    @Suppress("DEPRECATION")
    @Test fun oldBaseUrlConstantStillWorks() {
        assertEquals(OneS1ght.DEFAULT_BASE_URL, ApiClient.DEFAULT_BASE_URL)
        assertEquals("https://console.ones1ght.com/api/sdk/v1", OneS1ght.DEFAULT_BASE_URL)
    }

    /** iOS AnchorDiagnostic 은 matched·canPosition 을 그대로 둔다 — 안드도 경고 없이 같다. */
    @Test fun anchorDiagnosticMatchesIos() {
        val d = UwbPositioningProvider.AnchorDiagnostic(listOf(1), listOf(1), listOf(1), emptyList(), true, "s")
        assertEquals(d.received, d.matched)
        assertEquals(d.hasFix, d.canPosition)
        val getter = UwbPositioningProvider.AnchorDiagnostic::class.java.getMethod("getMatched")
        assertTrue(getter.getAnnotation(Deprecated::class.java) == null)
    }

    /** StopReason 은 iOS 와 같은 두 갈래. */
    @Test fun stopReasonMatchesIos() {
        assertEquals(listOf("ENDED", "ENGINE_FAILED"), FloorSession.StopReason.entries.map { it.name })
        assertNotNull(SessionStoppedListener::class.java.getMethod("onStopped", FloorSession.StopReason::class.java))
        assertNotNull(SessionFloorListener::class.java.getMethod("onFloorDetected", String::class.java))
    }
}
