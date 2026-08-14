package hk.uwu.soundman.log

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLogTest {
    @Test
    fun onlyDebugBuildsEmit() {
        assertTrue(AppLog.shouldEmit(debugBuild = true))
        assertFalse(AppLog.shouldEmit(debugBuild = false))
    }
}
