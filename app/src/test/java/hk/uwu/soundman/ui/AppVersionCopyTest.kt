package hk.uwu.soundman.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class AppVersionCopyTest {
    @Test
    fun formatsNameHashBuildAndChannel() {
        assertEquals(
            "1.0.0-d49c5ae-r1-dev",
            AppVersionCopy.moduleVersion("1.0.0", "d49c5ae", 1, "dev"),
        )
    }

    @Test
    fun keepsReleaseChannel() {
        assertEquals(
            "1.2.0-abc1234-r42-canary",
            AppVersionCopy.moduleVersion("1.2.0", "abc1234", 42, "canary"),
        )
    }
}
