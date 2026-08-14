package hk.uwu.soundman.hook.scopes.systemui.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemUiVolumeEntryLayoutTest {
    @Test
    fun volumeColumnResourceNamesPreferDialogColumns() {
        assertEquals(
            listOf(
                "volume_dialog_columns",
                "volume_dialog_content",
                "volume_dialog_column_collapsed",
                "volume_column_view",
            ),
            SystemUiVolumeEntryLayout.VOLUME_COLUMN_RESOURCE_NAMES,
        )
    }

    @Test
    fun styleTemplateResourceNamesAreDndThenRinger() {
        assertEquals(
            listOf("dnd_layout", "ringer_layout"),
            SystemUiVolumeEntryLayout.STYLE_TEMPLATE_RESOURCE_NAMES,
        )
    }

    @Test
    fun resourcePackagesPutContextFirstAndDeduplicateFallbacks() {
        assertEquals(
            listOf("miui.systemui.plugin", "com.android.systemui"),
            SystemUiVolumeEntryLayout.resourcePackages("miui.systemui.plugin"),
        )
        assertEquals(
            listOf("com.android.systemui", "miui.systemui.plugin"),
            SystemUiVolumeEntryLayout.resourcePackages("com.android.systemui"),
        )
        assertEquals(
            listOf("vendor.overlay", "miui.systemui.plugin", "com.android.systemui"),
            SystemUiVolumeEntryLayout.resourcePackages("vendor.overlay"),
        )
        assertEquals(
            listOf("miui.systemui.plugin", "com.android.systemui"),
            SystemUiVolumeEntryLayout.resourcePackages("   "),
        )
    }

    @Test
    fun iconResourceNamesPreferMiplayPhone() {
        assertEquals(
            listOf("ic_miplay_phone", "miplay_phone"),
            SystemUiVolumeEntryLayout.ICON_RESOURCE_NAMES,
        )
    }

    @Test
    fun dndChildResourceNamesMatchCompactRingerButton() {
        assertEquals(
            listOf("miui_standard_btn", "bg_blur", "icon"),
            SystemUiVolumeEntryLayout.DND_CHILD_RESOURCE_NAMES,
        )
    }

    @Test
    fun buttonBackgroundResourceNamesMatchCollapsedRingerChrome() {
        assertEquals(
            listOf(
                "o3_miui_volume_ringer_btn_first_bg_collapsed",
                "o3_miui_volume_ringer_btn_first_bg_blur",
            ),
            SystemUiVolumeEntryLayout.BUTTON_BACKGROUND_RESOURCE_NAMES,
        )
    }

    @Test
    fun liveBlurClassNamesMatchOfficialRingerBlur() {
        assertEquals(
            listOf(
                "miui.systemui.util.MiBlurCompat",
                "miui.systemui.util.MiBackgroundStyle",
                "com.android.systemui.miui.volume.Util",
                "com.android.systemui.miui.volume.RingerButtonRes",
                "com.miui.blur.sdk.backdrop.a",
            ),
            SystemUiVolumeEntryLayout.LIVE_BLUR_CLASS_NAMES,
        )
    }

    @Test
    fun blurBackgroundResourceNamesMatchRingerBlur() {
        assertEquals(
            listOf(
                "o3_miui_volume_ringer_bg_blur",
                "o3_miui_volume_ringer_bg_blur_cc",
            ),
            SystemUiVolumeEntryLayout.BLUR_BACKGROUND_RESOURCE_NAMES,
        )
    }

    @Test
    fun buttonRadiusDimenNamesMatchRingerBtnRadius() {
        assertEquals(
            listOf("o3_miui_ringer_btn_radius"),
            SystemUiVolumeEntryLayout.BUTTON_RADIUS_DIMEN_NAMES,
        )
    }

    @Test
    fun entryVisibilityHidesWhenExpanded() {
        assertEquals(8, SystemUiVolumeEntryLayout.entryVisibility(true))
        assertEquals(0, SystemUiVolumeEntryLayout.entryVisibility(false))
    }

    @Test
    fun circularButtonSpecMatchesCompactSidebarCircle() {
        val spec = SystemUiVolumeEntryLayout.circularButtonSpec()

        assertEquals(48, spec.sizeDp)
        assertEquals(4, spec.marginVerticalDp)
        assertTrue(spec.wrapContent)
        assertTrue(spec.centerHorizontal)
        assertTrue(spec.oval)
        assertEquals(0xFFFFFFFF.toInt(), spec.fillArgb)
    }
}
