package hk.uwu.soundman.hook.scopes.systemui.hidden

import hk.uwu.soundman.hook.scopes.systemui.hidden.SystemUiPluginHookTargets.MIUI_PLUGIN_PACKAGE

/**
 * 决定是否把某个 `PluginInstance` 的插件 ClassLoader 交给音量 Hook 安装。
 *
 * 动机：`loadPlugin` 和 `getPlugin` 会反复进入同一条提取路径；同一 ClassLoader 不得重复 hook，
 * 插件 reload 换了 ClassLoader 则必须重新安装。这个 gate 可被 JVM 单测直接调用。
 * 其它插件包直接忽略；同一 ClassLoader 第二次到达直接 return。
 */
class SystemUiPluginClassLoaderAttach {
    @Volatile
    private var attachedClassLoader: ClassLoader? = null

    /**
     * 读取 pluginInstance；仅 `miui.systemui.plugin` 且 ClassLoader 与上次不同时回调。
     *
     * @param pluginInstance `PluginInstance` 运行时对象
     * @param reader 包名和 ClassLoader 提取器
     * @param onAttached 新的插件 ClassLoader 就绪后回调
     */
    fun attach(
        pluginInstance: Any,
        reader: SystemUiPluginClassLoader,
        onAttached: (ClassLoader) -> Unit,
    ) {
        if (reader.packageName(pluginInstance) != MIUI_PLUGIN_PACKAGE) {
            return
        }
        val classLoader = reader.classLoader(pluginInstance)
        val previous: ClassLoader?
        synchronized(this) {
            if (classLoader === attachedClassLoader) {
                return
            }
            previous = attachedClassLoader
            attachedClassLoader = classLoader
        }
        try {
            onAttached(classLoader)
        } catch (error: Throwable) {
            synchronized(this) {
                if (attachedClassLoader === classLoader) {
                    attachedClassLoader = previous
                }
            }
            throw error
        }
    }
}
