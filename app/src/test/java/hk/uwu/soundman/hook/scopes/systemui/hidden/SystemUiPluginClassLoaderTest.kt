package hk.uwu.soundman.hook.scopes.systemui.hidden

import hk.uwu.soundman.hook.scopes.systemui.hidden.fakes.FakeClassLoaderFactory
import hk.uwu.soundman.hook.scopes.systemui.hidden.fakes.FakeClassLoaderFactoryWithoutGet
import hk.uwu.soundman.hook.scopes.systemui.hidden.fakes.FakePluginFactory
import hk.uwu.soundman.hook.scopes.systemui.hidden.fakes.FakePluginFactoryWithoutClassLoaderFactory
import hk.uwu.soundman.hook.scopes.systemui.hidden.fakes.FakePluginInstance
import hk.uwu.soundman.hook.scopes.systemui.hidden.fakes.FakePluginInstanceWithoutGetPackage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemUiPluginClassLoaderTest {
    private val reader = SystemUiPluginClassLoader()
    private val pluginClassLoader = object : ClassLoader() {}

    @Test
    fun packageNameReadsGetPackage() {
        val instance = pluginInstance("miui.systemui.plugin")
        assertEquals("miui.systemui.plugin", reader.packageName(instance))
    }

    @Test
    fun classLoaderFollowsFactoryChain() {
        val instance = pluginInstance("miui.systemui.plugin")
        assertSame(pluginClassLoader, reader.classLoader(instance))
    }

    @Test
    fun failsWhenGetPackageIsMissing() {
        val error = assertThrows(IllegalStateException::class.java) {
            reader.packageName(FakePluginInstanceWithoutGetPackage(FakePluginFactory(null)))
        }
        assertTrue(error.message.orEmpty().contains(SystemUiPluginHookTargets.GET_PACKAGE))
        assertTrue(error.message.orEmpty().contains(FakePluginInstanceWithoutGetPackage::class.java.name))
    }

    @Test
    fun failsWhenClassLoaderFactoryFieldIsMissing() {
        val instance = FakePluginInstance(
            "miui.systemui.plugin",
            FakePluginFactoryWithoutClassLoaderFactory(),
        )
        val error = assertThrows(IllegalStateException::class.java) {
            reader.classLoader(instance)
        }
        assertTrue(error.message.orEmpty().contains(SystemUiPluginHookTargets.FIELD_CLASS_LOADER_FACTORY))
        assertTrue(error.message.orEmpty().contains(FakePluginFactoryWithoutClassLoaderFactory::class.java.name))
    }

    @Test
    fun failsWhenGetIsMissing() {
        val instance = FakePluginInstance(
            "miui.systemui.plugin",
            FakePluginFactory(FakeClassLoaderFactoryWithoutGet()),
        )
        val error = assertThrows(IllegalStateException::class.java) {
            reader.classLoader(instance)
        }
        assertTrue(error.message.orEmpty().contains(SystemUiPluginHookTargets.METHOD_GET))
        assertTrue(error.message.orEmpty().contains(FakeClassLoaderFactoryWithoutGet::class.java.name))
    }

    @Test
    fun failsWhenClassLoaderFactoryIsNull() {
        val instance = FakePluginInstance("miui.systemui.plugin", FakePluginFactory(null))
        val error = assertThrows(IllegalStateException::class.java) {
            reader.classLoader(instance)
        }
        assertTrue(error.message.orEmpty().contains(SystemUiPluginHookTargets.FIELD_CLASS_LOADER_FACTORY))
        assertTrue(error.message.orEmpty().contains(FakePluginFactory::class.java.name))
        assertTrue(error.message.orEmpty().contains("null"))
    }

    @Test
    fun failsWhenGetReturnsNonClassLoader() {
        val instance = FakePluginInstance(
            "miui.systemui.plugin",
            FakePluginFactory(FakeClassLoaderFactory("not-a-classloader")),
        )
        val error = assertThrows(IllegalStateException::class.java) {
            reader.classLoader(instance)
        }
        assertTrue(error.message.orEmpty().contains(SystemUiPluginHookTargets.METHOD_GET))
        assertTrue(error.message.orEmpty().contains(FakeClassLoaderFactory::class.java.name))
        assertTrue(error.message.orEmpty().contains(String::class.java.name))
    }

    @Test
    fun unwrapsInvocationTargetExceptionFromGet() {
        val original = IllegalStateException("factory boom")
        val factory = FakeClassLoaderFactory(pluginClassLoader)
        factory.throwOnGet = original
        val instance = FakePluginInstance("miui.systemui.plugin", FakePluginFactory(factory))
        assertSame(original, assertThrows(IllegalStateException::class.java) {
            reader.classLoader(instance)
        })
    }

    private fun pluginInstance(packageName: String): FakePluginInstance =
        FakePluginInstance(
            packageName,
            FakePluginFactory(FakeClassLoaderFactory(pluginClassLoader)),
        )
}
