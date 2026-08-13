package hk.uwu.soundman.hook.scopes.systemui

import hk.uwu.soundman.hook.scopes.systemui.hidden.SystemUiPluginHookTargets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemUiVolumeEntryHookerTest {
    @Test
    fun hooksOnlyRingerModeLayoutLifecycle() {
        val targets = SystemUiVolumeEntryHooker.HOOK_TARGETS
        val classNames = targets.map { it.className }

        assertEquals(1, targets.size)
        assertEquals(
            "com.android.systemui.miui.volume.MiuiRingerModeLayout",
            targets.single().className,
        )
        assertEquals(
            listOf("onFinishInflate", "onAttachedToWindow", "updateExpandedH"),
            targets.single().methodNames,
        )
        assertFalse(targets.single().methodNames.contains("setZenModeByUser"))
        assertFalse(classNames.contains("miui.systemui.volume.VolumeDialogPlugin"))
        assertFalse(classNames.contains("miui.systemui.volume.VolumePanelViewController"))
        assertFalse(classNames.contains("com.android.systemui.miui.volume.VolumePanelViewController"))
        assertFalse(classNames.contains("com.android.systemui.miui.volume.MiuiVolumeDialogView"))
    }

    @Test
    fun watchesPluginInstanceLoadAndGet() {
        val targets = SystemUiVolumeEntryHooker.PLUGIN_WATCH_TARGETS

        assertEquals(1, targets.size)
        assertEquals(
            SystemUiPluginHookTargets.PLUGIN_INSTANCE_CLASS,
            targets.single().className,
        )
        assertEquals(
            "com.android.systemui.shared.plugins.PluginInstance",
            targets.single().className,
        )
        assertEquals(
            listOf(
                SystemUiPluginHookTargets.LOAD_PLUGIN,
                SystemUiPluginHookTargets.GET_PLUGIN,
            ),
            targets.single().methodNames,
        )
        assertEquals(listOf("loadPlugin", "getPlugin"), targets.single().methodNames)
    }

    @Test
    fun safeResolveReturnsBlockResult() {
        val expected = listOf("onFinishInflate", "onAttachedToWindow")
        var failed: Throwable? = null

        val resolved = safeResolve(
            block = { expected },
            onFailure = { failed = it },
        )

        assertSame(expected, resolved)
        assertEquals(null, failed)
    }

    @Test
    fun safeResolveSwallowsNoSuchMethodException() {
        val missing = NoSuchMethodException("onFinishInflate")
        var failed: Throwable? = null

        val resolved = safeResolve<String>(
            block = { throw missing },
            onFailure = { failed = it },
        )

        assertTrue(resolved.isEmpty())
        assertSame(missing, failed)
    }
}
