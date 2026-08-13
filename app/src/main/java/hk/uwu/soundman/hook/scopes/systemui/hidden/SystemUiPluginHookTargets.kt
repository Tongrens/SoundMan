package hk.uwu.soundman.hook.scopes.systemui.hidden

/**
 * SystemUI 插件 Hook 的稳定目标名。
 *
 * 动机：package-ready / LSPosed scope 只进 `com.android.systemui`；
 * `miui.systemui.plugin` 不是独立进程，必须经 PluginInstance 取出插件 ClassLoader。
 * allowlist、scope、提取器和单测共用这里的名字，禁止再散落字符串。
 */
object SystemUiPluginHookTargets {
    /** package-ready / LSPosed scope 唯一允许的 SystemUI 进程包名。 */
    const val SYSTEM_UI_PACKAGE = "com.android.systemui"

    /** 音量插件包名；只用于 `getPackage()` 过滤和查资源，不是 hook 进程。 */
    const val MIUI_PLUGIN_PACKAGE = "miui.systemui.plugin"

    /** SystemUI 里加载插件的宿主类。 */
    const val PLUGIN_INSTANCE_CLASS = "com.android.systemui.shared.plugins.PluginInstance"

    /** 插件加载入口；after 时 factory 链已就绪。 */
    const val LOAD_PLUGIN = "loadPlugin"

    /** 热重载时插件可能已经 loaded 的正式读取入口。 */
    const val GET_PLUGIN = "getPlugin"

    /** 读取插件包名。 */
    const val GET_PACKAGE = "getPackage"

    /** `PluginInstance` 上的 factory 字段。 */
    const val FIELD_PLUGIN_FACTORY = "mPluginFactory"

    /** factory 上的 ClassLoader factory 字段。 */
    const val FIELD_CLASS_LOADER_FACTORY = "mClassLoaderFactory"

    /** ClassLoader factory 的无参 `get()`。 */
    const val METHOD_GET = "get"
}
