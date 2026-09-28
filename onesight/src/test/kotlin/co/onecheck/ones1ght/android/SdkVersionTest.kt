package co.onecheck.ones1ght.android

import org.junit.Assert.assertTrue
import org.junit.Test

class SdkVersionTest {
    @Test fun sdkVersionIsSemver() {
        assertTrue(Regex("""^\d+\.\d+\.\d+$""").matches(OneS1ght.SDK_VERSION))
    }
}
