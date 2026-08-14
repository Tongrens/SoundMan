package hk.uwu.soundman.hook.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YLogTest {
    @Test
    fun onlyDebugBuildsEmit() {
        assertTrue(YLog.shouldEmit(debugBuild = true))
        assertFalse(YLog.shouldEmit(debugBuild = false))
    }
}
