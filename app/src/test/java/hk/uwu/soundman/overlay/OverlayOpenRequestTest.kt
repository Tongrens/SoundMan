package hk.uwu.soundman.overlay

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayOpenRequestTest {
    @Test
    fun nullExtrasMeanNotFromSidebar() {
        assertFalse(OverlayOpenRequest.parseFromVolumeSidebar(null))
        assertFalse(OverlayOpenRequest.fromExtras(null).fromVolumeSidebar)
    }

    @Test
    fun missingExtraMeansNotFromSidebar() {
        assertFalse(OverlayOpenRequest.parseFromVolumeSidebar(emptyMap()))
        assertFalse(OverlayOpenRequest.parseFromVolumeSidebar(mapOf("other.extra" to true)))
        assertFalse(OverlayOpenRequest.fromExtras(emptyMap()).fromVolumeSidebar)
    }

    @Test
    fun extraTrueMeansFromSidebar() {
        assertTrue(
            OverlayOpenRequest.parseFromVolumeSidebar(
                mapOf(OverlayOpenRequest.EXTRA_FROM_VOLUME_SIDEBAR to true),
            ),
        )
        assertTrue(
            OverlayOpenRequest.fromExtras(
                mapOf(OverlayOpenRequest.EXTRA_FROM_VOLUME_SIDEBAR to true),
            ).fromVolumeSidebar,
        )
    }

    @Test
    fun extrasRoundTripTrueAndFalse() {
        val fromSidebar = OverlayOpenRequest(true)
        val fromDesktop = OverlayOpenRequest(false)

        assertTrue(OverlayOpenRequest.fromExtras(fromSidebar.extras()).fromVolumeSidebar)
        assertFalse(OverlayOpenRequest.fromExtras(fromDesktop.extras()).fromVolumeSidebar)
        assertEquals(true, OverlayOpenRequest.parseFromVolumeSidebar(fromSidebar.extras()))
        assertEquals(false, OverlayOpenRequest.parseFromVolumeSidebar(fromDesktop.extras()))
    }

    @Test
    fun volumeSidebarDismissesWithBackDownThenUp() {
        assertEquals(
            listOf(
                DismissKeyStroke(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK),
                DismissKeyStroke(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK),
            ),
            OverlayOpenRequest.volumeSidebarDismissSequence(),
        )
        assertEquals(0, KeyEvent.ACTION_DOWN)
        assertEquals(1, KeyEvent.ACTION_UP)
        assertEquals(4, KeyEvent.KEYCODE_BACK)
    }
}
