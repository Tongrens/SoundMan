package hk.uwu.soundman.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PanelPageKeyTest {
    @Test
    fun keepsDevicePageWhenPackageStillVisible() {
        assertEquals(
            "com.example.player",
            PanelPageKey.of("com.example.player", listOf("com.example.player", "com.other")),
        )
    }

    @Test
    fun returnsToListWhenPackageLeavesVisibleSet() {
        assertNull(PanelPageKey.of("com.example.player", listOf("com.other")))
        assertNull(PanelPageKey.of(null, listOf("com.example.player")))
        assertNull(PanelPageKey.of("   ", listOf("com.example.player")))
    }
}
