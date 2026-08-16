package hk.uwu.soundman.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayWindowRevealTest {
    @Test
    fun hiddenRevealClearsDimAndBlur() {
        val chrome = OverlayWindowReveal.chrome(0f)
        assertEquals(0f, chrome.dimAmount, 0.0001f)
        assertEquals(0, chrome.blurRadiusPx)
        assertFalse(chrome.blurEnabled)
    }

    @Test
    fun fullRevealMatchesStableTokens() {
        val chrome = OverlayWindowReveal.chrome(1f)
        assertEquals(OverlayWindowReveal.DIM_AMOUNT, chrome.dimAmount, 0.0001f)
        assertEquals(OverlayWindowReveal.BLUR_RADIUS_PX, chrome.blurRadiusPx)
        assertTrue(chrome.blurEnabled)
    }

    @Test
    fun halfRevealScalesStableTokens() {
        val chrome = OverlayWindowReveal.chrome(0.5f)
        assertEquals(OverlayWindowReveal.DIM_AMOUNT * 0.5f, chrome.dimAmount, 0.0001f)
        assertEquals(OverlayWindowReveal.BLUR_RADIUS_PX / 2, chrome.blurRadiusPx)
    }

    @Test
    fun rejectsRevealOutsideUnitRange() {
        assertThrows(IllegalArgumentException::class.java) {
            OverlayWindowReveal.chrome(-0.01f)
        }
        assertThrows(IllegalArgumentException::class.java) {
            OverlayWindowReveal.chrome(1.01f)
        }
    }
}
