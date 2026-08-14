package hk.uwu.soundman.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class XposedStatusCopyTest {
    @Test
    fun executorOnlyShownWhenActive() {
        assertTrue(XposedStatusCopy.showExecutor(active = true))
        assertFalse(XposedStatusCopy.showExecutor(active = false))
    }

    @Test
    fun blankExecutorNameFallsBackToXposed() {
        assertEquals("Xposed", XposedStatusCopy.executorName(""))
        assertEquals("Xposed", XposedStatusCopy.executorName("   "))
        assertEquals("LSPosed", XposedStatusCopy.executorName("LSPosed"))
    }

    @Test
    fun unknownApiLevelOmitsApiSuffix() {
        assertEquals("LSPosed", XposedStatusCopy.executorLine("LSPosed", 0))
        assertEquals("LSPosed · API 93", XposedStatusCopy.executorLine("LSPosed", 93))
    }
}
