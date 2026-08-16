package hk.uwu.soundman.hook.scopes.systemui.hidden

import android.content.Context
import android.graphics.drawable.Drawable
import android.util.Log
import android.view.View
import java.lang.reflect.InvocationTargetException

/**
 * 复用 SystemUI 音量面板展开态的官方材质链路。
 *
 * 这些类仅存在于 miui.systemui.plugin ClassLoader，编译期不可链接，因此这里集中进行反射适配。
 * advanced material 完整调用失败时才依次尝试官方 S 版 blur 和静态展开背景。
 */
class OfficialExpandedMaterial(
    private val classLoader: ClassLoader,
    private val context: Context,
    private val log: (priority: Int, tag: String, message: String, throwable: Throwable?) -> Unit,
) {
    fun apply(view: View): Mode {
        check(view.isAttachedToWindow) { "Official expanded material requires an attached View" }
        applyOutline(view)
        view.background = null
        val advanced = isAdvancedMaterialEffective()
        val lowEnd = isLowEndDevice()
        val defaultPluginTheme = isDefaultPluginTheme()
        return when (OfficialExpandedMaterialPolicy.choose(advanced, lowEnd, defaultPluginTheme)) {
            OfficialExpandedMaterialMode.ADVANCED -> {
                check(applyAdvanced(view)) { "Official advanced volume material application failed" }
                Mode.ADVANCED
            }

            OfficialExpandedMaterialMode.BLUR_FOR_S -> {
                check(applyBlurForS(view)) { "Official S volume blur application failed" }
                Mode.BLUR_FOR_S
            }

            OfficialExpandedMaterialMode.STATIC -> {
                view.background = expandedBackground()
                Mode.STATIC
            }
        }
    }

    fun applyOutline(view: View): Int {
        val radius = invokeRequired(RES, "getBgRadius", context) as? Int
            ?: error("MiuiVolumeDialogRes.getBgRadius returned non-Int")
        val applied = invokeRequired(
            MI_BLUR_COMPAT,
            "setOutlineRoundRect",
            view,
            radius.toFloat(),
            isAdvancedMaterialEffective()
        )
        check(applied !== FAILED) { "MiBlurCompat.setOutlineRoundRect failed" }
        view.clipToOutline = true
        return radius
    }

    fun clear(view: View) {
        invokeOptional(UTIL, "setMiBgBlur", view, 0, false)
        invokeOptional(UTIL, "setViewBlurForS", view, 0)
        view.background = null
    }

    private fun isAdvancedMaterialEffective(): Boolean =
        invokeRequired(UTIL, "isAdvancedMaterialEffective", context) as? Boolean
            ?: error("Util.isAdvancedMaterialEffective returned non-Boolean")

    private fun isLowEndDevice(): Boolean =
        invokeRequired(BLUR_UTILS, "isLowEndDevice") as? Boolean
            ?: error("BlurUtils.isLowEndDevice returned non-Boolean")

    private fun isDefaultPluginTheme(): Boolean {
        val themeClass = loadClass(THEME_UTILS) ?: error("ThemeUtils class unavailable")
        val instance = themeClass.getField("INSTANCE").get(null)
        return invokeRequired(themeClass, instance, "getDefaultPluginTheme") as? Boolean
            ?: error("ThemeUtils.getDefaultPluginTheme returned non-Boolean")
    }

    private fun applyAdvanced(view: View): Boolean {
        val blandColor = invokeRequired(RES, "getBgBlandColor", true).takeUnless { it === FAILED }
            ?: return false
        val styleClass = loadClass(STYLE) ?: return false
        val instance = runCatching { styleClass.getField("INSTANCE").get(null) }
            .onFailure { log(Log.ERROR, TAG, "Unable to resolve MiBackgroundStyle.INSTANCE", it) }
            .getOrNull() ?: return false
        val glassToken = invokeRequired(styleClass, instance, "getVOLUMPANEL_EXPAND_GLASS_TOKEN")
            .takeUnless { it === FAILED } ?: return false
        val styled =
            invokeRequired(UTIL, "setMiViewBackgroundStyle", view, 1, blandColor, glassToken)
        if (styled === FAILED) return false
        val radius = invokeRequired(RES, "getBlandBlurRadius", context) as? Int ?: return false
        return invokeRequired(UTIL, "setMiBgBlur", view, radius, true) !== FAILED
    }

    private fun applyBlurForS(view: View): Boolean {
        val radius = invokeRequired(RES, "getBgRadius", context) as? Int ?: return false
        return invokeOptional(UTIL, "setViewBlurForS", view, radius) !== FAILED
    }

    private fun expandedBackground(): Drawable {
        val resId = invokeRequired(RES, "getBgRes", true) as? Int
            ?: error("MiuiVolumeDialogRes.getBgRes returned non-Int")
        require(resId != 0) { "MiuiVolumeDialogRes.getBgRes returned zero" }
        return context.resources.getDrawable(resId, context.theme)
            ?: error("Official expanded background drawable is unavailable")
    }

    private fun invokeRequired(className: String, methodName: String, vararg args: Any?): Any? {
        val clazz = loadClass(className) ?: return FAILED
        return invokeRequired(clazz, null, methodName, *args)
    }

    private fun invokeRequired(
        clazz: Class<*>,
        target: Any?,
        methodName: String,
        vararg args: Any?
    ): Any? {
        val method = (clazz.methods.asSequence() + clazz.declaredMethods.asSequence()).firstOrNull {
            it.name == methodName && parametersMatch(it.parameterTypes, args)
        } ?: run {
            log(Log.ERROR, TAG, "Official material method missing: ${clazz.name}.$methodName", null)
            return FAILED
        }
        method.isAccessible = true
        return try {
            method.invoke(target, *args)
        } catch (error: InvocationTargetException) {
            log(
                Log.ERROR,
                TAG,
                "Official material call failed: ${clazz.name}.$methodName",
                error.cause ?: error
            )
            FAILED
        } catch (error: ReflectiveOperationException) {
            log(Log.ERROR, TAG, "Official material call failed: ${clazz.name}.$methodName", error)
            FAILED
        } catch (error: IllegalArgumentException) {
            log(
                Log.ERROR,
                TAG,
                "Official material arguments rejected: ${clazz.name}.$methodName",
                error
            )
            FAILED
        }
    }

    private fun invokeOptional(className: String, methodName: String, vararg args: Any?): Any? {
        val clazz = try {
            classLoader.loadClass(className)
        } catch (_: ClassNotFoundException) {
            return FAILED
        }
        val method = (clazz.methods.asSequence() + clazz.declaredMethods.asSequence()).firstOrNull {
            it.name == methodName && parametersMatch(it.parameterTypes, args)
        } ?: return FAILED
        method.isAccessible = true
        return try {
            method.invoke(null, *args)
        } catch (error: Throwable) {
            log(
                Log.WARN,
                TAG,
                "Optional official material call failed: ${clazz.name}.$methodName",
                error
            )
            FAILED
        }
    }

    private fun loadClass(name: String): Class<*>? = try {
        classLoader.loadClass(name)
    } catch (error: ClassNotFoundException) {
        log(Log.ERROR, TAG, "Official material class missing: $name", error)
        null
    }

    private fun parametersMatch(types: Array<Class<*>>, args: Array<out Any?>): Boolean {
        if (types.size != args.size) return false
        return types.indices.all { index ->
            val value = args[index] ?: return@all !types[index].isPrimitive
            types[index].isInstance(value) || when (types[index]) {
                java.lang.Boolean.TYPE -> value is Boolean
                java.lang.Integer.TYPE -> value is Int
                java.lang.Float.TYPE -> value is Float
                else -> false
            }
        }
    }

    enum class Mode { ADVANCED, BLUR_FOR_S, STATIC }

    private companion object {
        const val TAG = "SoundMan.ExpandedMaterial"
        const val UTIL = "com.android.systemui.miui.volume.Util"
        const val RES = "com.android.systemui.miui.volume.MiuiVolumeDialogRes"
        const val STYLE = "miui.systemui.util.MiBackgroundStyle"
        const val MI_BLUR_COMPAT = "miui.systemui.util.MiBlurCompat"
        const val BLUR_UTILS = "miui.systemui.util.BlurUtils"
        const val THEME_UTILS = "miui.systemui.util.ThemeUtils"
        val FAILED = Any()
    }
}

enum class OfficialExpandedMaterialMode { ADVANCED, BLUR_FOR_S, STATIC }

object OfficialExpandedMaterialPolicy {
    fun choose(
        advancedMaterialEffective: Boolean,
        lowEndDevice: Boolean,
        defaultPluginTheme: Boolean,
    ): OfficialExpandedMaterialMode = when {
        advancedMaterialEffective -> OfficialExpandedMaterialMode.ADVANCED
        !lowEndDevice && defaultPluginTheme -> OfficialExpandedMaterialMode.BLUR_FOR_S
        else -> OfficialExpandedMaterialMode.STATIC
    }
}
