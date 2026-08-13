package hk.uwu.soundman.hook.scopes.systemui

import android.util.Log
import android.view.View
import com.highcapable.kavaref.KavaRef.Companion.resolve
import com.highcapable.yukihookapi.hook.entity.YukiBaseHooker
import hk.uwu.soundman.hook.core.YLog
import hk.uwu.soundman.hook.scopes.systemui.hidden.SystemUiPluginClassLoader
import hk.uwu.soundman.hook.scopes.systemui.hidden.SystemUiPluginClassLoaderAttach
import hk.uwu.soundman.hook.scopes.systemui.hidden.SystemUiPluginHookTargets
import hk.uwu.soundman.hook.scopes.systemui.runtime.SystemUiVolumeEntryRuntime

/**
 * 在 HyperOS 音量侧栏的静音/免打扰按钮下方插入 SoundMan 圆形入口。
 *
 * 本 hooker 只跑在 SystemUI 进程：先在 SystemUI ClassLoader 上监视
 * `PluginInstance.loadPlugin` / `getPlugin`，取出 `miui.systemui.plugin`
 * 的插件 ClassLoader 后再 hook `MiuiRingerModeLayout`。
 * 缺 PluginInstance、提取失败或缺音量类只打日志，不得让异常打穿 SystemUI。
 */
object SystemUiVolumeEntryHooker : YukiBaseHooker() {
    private val runtime = SystemUiVolumeEntryRuntime(log = ::writeLog)
    private val pluginClassLoaderReader = SystemUiPluginClassLoader()
    private val pluginClassLoaderAttach = SystemUiPluginClassLoaderAttach()

    override fun onHook() {
        PLUGIN_WATCH_TARGETS.forEach(::watchPluginTarget)
    }

    private fun watchPluginTarget(target: SystemUiVolumeEntryHookTarget) {
        val clazz = runCatching { target.className.toClass() }
            .onFailure { YLog.warn("Plugin watch class missing: ${target.className}", it) }
            .getOrNull()
            ?: return
        val resolved = clazz.resolve().optional()
        target.methodNames.forEach { methodName ->
            var resolutionFailed = false
            val methods = safeResolve(
                block = { resolved.method { name = methodName } },
                onFailure = { error ->
                    resolutionFailed = true
                    YLog.warn(
                        "Plugin watch method missing: class=${target.className} method=$methodName",
                        error,
                    )
                },
            )
            if (methods.isEmpty()) {
                if (!resolutionFailed) {
                    YLog.warn(
                        "Plugin watch method missing: class=${target.className} method=$methodName",
                    )
                }
                return@forEach
            }
            methods.forEach { method ->
                method.hook {
                    after {
                        if (throwable != null) return@after
                        val pluginInstance = instanceOrNull
                        if (pluginInstance == null) {
                            YLog.error("Plugin watch has no instance: ${target.className}#$methodName")
                            return@after
                        }
                        attachPluginClassLoader(pluginInstance)
                    }
                }
            }
            methods.forEach { method ->
                YLog.info(
                    "Installed plugin watch: class=${clazz.name} method=${method.self.toGenericString()}",
                )
            }
        }
    }

    private fun attachPluginClassLoader(pluginInstance: Any) {
        try {
            pluginClassLoaderAttach.attach(pluginInstance, pluginClassLoaderReader) { pluginClassLoader ->
                YLog.info(
                    "Attached ${SystemUiPluginHookTargets.MIUI_PLUGIN_PACKAGE} ClassLoader: " +
                        pluginClassLoader.javaClass.name,
                )
                runtime.attachPluginClassLoader(pluginClassLoader)
                installVolumeHooks(pluginClassLoader)
            }
        } catch (error: Throwable) {
            YLog.error(
                "Unable to attach ${SystemUiPluginHookTargets.MIUI_PLUGIN_PACKAGE} ClassLoader " +
                    "from ${pluginInstance.javaClass.name}",
                error,
            )
        }
    }

    private fun installVolumeHooks(pluginClassLoader: ClassLoader) {
        HOOK_TARGETS.forEach { target -> hookTarget(target, pluginClassLoader) }
    }

    private fun hookTarget(target: SystemUiVolumeEntryHookTarget, pluginClassLoader: ClassLoader) {
        val clazz = runCatching { target.className.toClass(pluginClassLoader) }
            .onFailure { YLog.warn("Volume hook class missing: ${target.className}", it) }
            .getOrNull()
            ?: return
        val resolved = clazz.resolve().optional()
        target.methodNames.forEach { methodName ->
            var resolutionFailed = false
            val methods = safeResolve(
                block = { resolved.method { name = methodName } },
                onFailure = { error ->
                    resolutionFailed = true
                    YLog.warn(
                        "Volume hook method missing: class=${target.className} method=$methodName",
                        error,
                    )
                },
            )
            if (methods.isEmpty()) {
                if (!resolutionFailed) {
                    YLog.warn(
                        "Volume hook method missing: class=${target.className} method=$methodName",
                    )
                }
                return@forEach
            }
            methods.forEach { method ->
                method.hook {
                    after {
                        if (throwable != null) return@after
                        if (methodName == METHOD_UPDATE_EXPANDED_H) {
                            val expanded = args.getOrNull(0) as? Boolean
                            if (expanded == null) {
                                YLog.error(
                                    "updateExpandedH missing Boolean argument: " +
                                        "class=${target.className} arg0=${args.getOrNull(0)?.javaClass?.name}",
                                )
                                return@after
                            }
                            runtime.applyExpanded(instance as? View, expanded)
                            return@after
                        }
                        runtime.scheduleInsertion(instance, "${clazz.name}#$methodName")
                    }
                }
            }
            methods.forEach { method ->
                YLog.info(
                    "Installed volume hook: class=${clazz.name} method=${method.self.toGenericString()}",
                )
            }
        }
    }

    private fun writeLog(priority: Int, tag: String, message: String, throwable: Throwable?) {
        val text = "[$tag] $message"
        when (priority) {
            Log.DEBUG -> YLog.debug(text, throwable)
            Log.INFO -> YLog.info(text, throwable)
            Log.WARN -> YLog.warn(text, throwable)
            else -> YLog.error(text, throwable)
        }
    }

    /**
     * 当前模块实际安装的音量 Hook 目标。
     *
     * 只描述类名和方法名，必须用插件 ClassLoader 解析，方便单测断言挂载范围。
     */
    val HOOK_TARGETS: List<SystemUiVolumeEntryHookTarget> = listOf(
        SystemUiVolumeEntryHookTarget(
            className = CLASS_RINGER_MODE_LAYOUT,
            methodNames = listOf(
                METHOD_FINISH_INFLATE,
                METHOD_ATTACHED_TO_WINDOW,
                METHOD_UPDATE_EXPANDED_H,
            ),
        ),
    )

    /**
     * 在 SystemUI ClassLoader 上监视的插件入口。
     *
     * `loadPlugin` 覆盖首次加载；`getPlugin` 覆盖插件已经 loaded 的路径。
     */
    val PLUGIN_WATCH_TARGETS: List<SystemUiVolumeEntryHookTarget> = listOf(
        SystemUiVolumeEntryHookTarget(
            className = SystemUiPluginHookTargets.PLUGIN_INSTANCE_CLASS,
            methodNames = listOf(
                SystemUiPluginHookTargets.LOAD_PLUGIN,
                SystemUiPluginHookTargets.GET_PLUGIN,
            ),
        ),
    )

    private const val CLASS_RINGER_MODE_LAYOUT =
        "com.android.systemui.miui.volume.MiuiRingerModeLayout"
    private const val METHOD_FINISH_INFLATE = "onFinishInflate"
    private const val METHOD_ATTACHED_TO_WINDOW = "onAttachedToWindow"
    private const val METHOD_UPDATE_EXPANDED_H = "updateExpandedH"
}

/**
 * 音量侧栏入口的单个 Hook 目标。
 *
 * 只描述类名和方法名，不负责解析或安装。方法按名字匹配全部重载，不限定参数列表。
 *
 * @param className 目标类全名
 * @param methodNames 要 hook 的方法名，按安装顺序排列
 */
data class SystemUiVolumeEntryHookTarget(
    val className: String,
    val methodNames: List<String>,
)

/**
 * 安全执行 KavaRef 成员解析。
 *
 * `method { }` 找不到成员时会抛 [NoSuchMethodException]；单个方法缺失不得让整个 onHook 失败。
 *
 * @param block 实际解析逻辑，成功时返回匹配到的成员列表
 * @param onFailure 解析抛错时回调，调用方负责打日志
 * @return 解析结果；失败时返回 emptyList
 */
fun <T> safeResolve(block: () -> List<T>, onFailure: (Throwable) -> Unit): List<T> {
    return try {
        block()
    } catch (failure: Throwable) {
        onFailure(failure)
        emptyList()
    }
}
