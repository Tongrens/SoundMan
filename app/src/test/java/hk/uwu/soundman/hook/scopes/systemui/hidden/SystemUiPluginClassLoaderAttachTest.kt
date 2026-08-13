package hk.uwu.soundman.hook.scopes.systemui.hidden

import hk.uwu.soundman.hook.scopes.systemui.hidden.fakes.FakeClassLoaderFactory
import hk.uwu.soundman.hook.scopes.systemui.hidden.fakes.FakePluginFactory
import hk.uwu.soundman.hook.scopes.systemui.hidden.fakes.FakePluginInstance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemUiPluginClassLoaderAttachTest {
    private val reader = SystemUiPluginClassLoader()

    @Test
    fun otherPackageDoesNotAttach() {
        val attach = SystemUiPluginClassLoaderAttach()
        val attached = ArrayList<ClassLoader>()

        attach.attach(
            pluginInstance("com.android.systemui", object : ClassLoader() {}),
            reader,
        ) { attached += it }

        assertTrue(attached.isEmpty())
    }

    @Test
    fun sameClassLoaderDoesNotAttachTwice() {
        val attach = SystemUiPluginClassLoaderAttach()
        val loader = object : ClassLoader() {}
        val attached = ArrayList<ClassLoader>()
        val instance = pluginInstance(SystemUiPluginHookTargets.MIUI_PLUGIN_PACKAGE, loader)

        attach.attach(instance, reader) { attached += it }
        attach.attach(instance, reader) { attached += it }

        assertEquals(1, attached.size)
        assertSame(loader, attached.single())
    }

    @Test
    fun differentClassLoaderAttachesAgain() {
        val attach = SystemUiPluginClassLoaderAttach()
        val first = object : ClassLoader() {}
        val second = object : ClassLoader() {}
        val attached = ArrayList<ClassLoader>()

        attach.attach(
            pluginInstance(SystemUiPluginHookTargets.MIUI_PLUGIN_PACKAGE, first),
            reader,
        ) { attached += it }
        attach.attach(
            pluginInstance(SystemUiPluginHookTargets.MIUI_PLUGIN_PACKAGE, second),
            reader,
        ) { attached += it }

        assertEquals(2, attached.size)
        assertSame(first, attached[0])
        assertSame(second, attached[1])
    }

    @Test
    fun failedAttachAllowsRetryOnSameClassLoader() {
        val attach = SystemUiPluginClassLoaderAttach()
        val loader = object : ClassLoader() {}
        val attached = ArrayList<ClassLoader>()
        val instance = pluginInstance(SystemUiPluginHookTargets.MIUI_PLUGIN_PACKAGE, loader)
        val boom = IllegalStateException("install failed")

        val firstError = try {
            attach.attach(instance, reader) { throw boom }
            null
        } catch (error: IllegalStateException) {
            error
        }
        assertSame(boom, firstError)

        attach.attach(instance, reader) { attached += it }

        assertEquals(1, attached.size)
        assertSame(loader, attached.single())
    }

    private fun pluginInstance(packageName: String, loader: ClassLoader): FakePluginInstance =
        FakePluginInstance(
            packageName,
            FakePluginFactory(FakeClassLoaderFactory(loader)),
        )
}
