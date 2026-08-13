package hk.uwu.soundman.hook.scopes.systemui.hidden

import android.content.Context
import android.util.Log
import android.view.View
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

/**
 * 给 SoundMan 入口套上和折叠态静音/免打扰同一套 live MiBlur。
 *
 * 动机：官方圆钮的毛玻璃不是 `o3_miui_volume_ringer_bg_blur` 那张静态图。
 * 主题打开 blur 时走 `Util.setRoundRect` + `Util.setMiViewBlurAndBlendColor`，
 * 以及 `MiBlurCompat` 的 window blur；静态 drawable 盖上去颜色和 blur 都会错。
 *
 * 反射理由：`MiBlurCompat` / `Util` / `RingerButtonRes` / `MiuiColorBlendToken`
 * 只在 `miui.systemui.plugin` ClassLoader 里，编译 classpath 没有这些类，
 * 也没有可链接的公开 SDK。已确认 runtime Context 只能拿到资源，拿不到这些静态方法。
 */
class OfficialRingerBlur(
    private val pluginClassLoader: ClassLoader,
    private val log: (priority: Int, tag: String, message: String, throwable: Throwable?) -> Unit,
) {
    private val classes = ConcurrentHashMap<String, Class<*>>()
    private val methods = ConcurrentHashMap<MethodKey, Method>()

    /**
     * 当前主题是否走 live 背景模糊。
     *
     * `null` 表示探测 API 不存在或调用失败。
     */
    fun themeBlurOpened(context: Context): Boolean? {
        return invokeStatic<Boolean>(
            MI_BLUR_COMPAT,
            METHOD_THEME_BLUR_OPENED,
            arrayOf(Context::class.java),
            arrayOf(context),
        )
    }

    /**
     * 按折叠态免打扰 **chrome** 套 live blur。
     *
     * 官方只在 `miui_standard_btn` 上做 `setRoundRect` + `setMiViewBlurAndBlendColor`。
     * 不要再叠 `setMiBgBlur` / window blur，叠上去会比官方更深。
     *
     * @param view 圆钮 chrome
     * @param radiusPx 官方 `o3_miui_ringer_btn_radius`
     * @return blend 调用成功
     */
    fun applyCollapsedChrome(view: View, radiusPx: Int): Boolean {
        invokeInstanceOrStatic(VOLUME_UTIL, METHOD_SET_ROUND_RECT, view, radiusPx.toFloat())
        val token = collapsedOffBlendToken()
        if (token == null) {
            log(Log.ERROR, TAG, "Official ringer blend token was not resolved", null)
            return false
        }
        val applied = invokeInstanceOrStatic(
            VOLUME_UTIL,
            METHOD_SET_MI_VIEW_BLUR_AND_BLEND,
            view,
            1,
            token,
        )
        if (!applied) {
            log(Log.ERROR, TAG, "Official setMiViewBlurAndBlendColor was not applied", null)
        }
        return applied
    }

    /**
     * 创建官方 `bg_blur` 同款 Backdrop 层。
     *
     * JADX：`com.miui.blur.sdk.backdrop.a(Context)` + `setBlurEnabled(true)`。
     * 普通 View 贴 `o3_miui_volume_ringer_bg_blur` 会比 live backdrop 更深。
     */
    fun createCollapsedBlurLayer(context: Context, radiusPx: Int): View? {
        val clazz = loadClass(BACKDROP_BLUR_VIEW) ?: return null
        val created = try {
            clazz.getConstructor(Context::class.java).newInstance(context)
        } catch (error: ReflectiveOperationException) {
            log(Log.ERROR, TAG, "Unable to construct $BACKDROP_BLUR_VIEW", error)
            null
        } ?: return null
        val view = created as? View
        if (view == null) {
            log(Log.ERROR, TAG, "$BACKDROP_BLUR_VIEW is not a View: ${created.javaClass.name}", null)
            return null
        }
        val enabled = invokeOn(clazz, view, METHOD_SET_BLUR_ENABLED, arrayOf(true))
        if (enabled === FAILED) return null
        invokeOn(clazz, view, METHOD_SET_CORNER_RADIUS, arrayOf(radiusPx.toFloat()))
        return view
    }

    private fun collapsedOffBlendToken(): Any? {
        val fromRes = invokeStatic<Any>(
            RINGER_BUTTON_RES,
            METHOD_GET_BUTTON_BG_BLEND,
            arrayOf(
                Boolean::class.javaPrimitiveType!!,
                Boolean::class.javaPrimitiveType!!,
                Boolean::class.javaPrimitiveType!!,
            ),
            arrayOf(false, true, false),
        )
        if (fromRes != null) return fromRes
        val holder = loadClass(MIUI_COLOR_BLEND_TOKEN) ?: return null
        val instance = runCatching {
            holder.getField(FIELD_INSTANCE).get(null)
        }.onFailure { error ->
            log(Log.ERROR, TAG, "Unable to read $MIUI_COLOR_BLEND_TOKEN.$FIELD_INSTANCE", error)
        }.getOrNull() ?: return null
        return invokeOn(holder, instance, METHOD_GET_RINGER_BG_OFF, emptyArray())
    }

    private fun invokeInstanceOrStatic(className: String, methodName: String, vararg args: Any?): Boolean {
        val clazz = loadClass(className) ?: return false
        return invokeOn(clazz, null, methodName, args) != FAILED
    }

    private fun <T> invokeStatic(
        className: String,
        methodName: String,
        parameterTypes: Array<Class<*>>,
        args: Array<Any?>,
    ): T? {
        val clazz = loadClass(className) ?: return null
        val result = invokeOn(clazz, null, methodName, args) ?: return null
        if (result === FAILED) return null
        @Suppress("UNCHECKED_CAST")
        return result as? T
    }

    private fun invokeOn(
        clazz: Class<*>,
        target: Any?,
        methodName: String,
        args: Array<out Any?>,
    ): Any? {
        val method = resolveMethod(clazz, methodName, args) ?: return FAILED
        return try {
            method.invoke(target, *args)
        } catch (error: InvocationTargetException) {
            log(Log.ERROR, TAG, "Official blur call failed: ${clazz.name}.$methodName", error.cause ?: error)
            FAILED
        } catch (error: ReflectiveOperationException) {
            log(Log.ERROR, TAG, "Official blur call failed: ${clazz.name}.$methodName", error)
            FAILED
        } catch (error: IllegalArgumentException) {
            log(Log.ERROR, TAG, "Official blur arguments rejected: ${clazz.name}.$methodName", error)
            FAILED
        }
    }

    private fun resolveMethod(clazz: Class<*>, methodName: String, args: Array<out Any?>): Method? {
        val key = MethodKey(clazz, methodName, args.size)
        methods[key]?.let { return it }
        val match = clazz.methods.firstOrNull { method ->
            method.name == methodName && parametersMatch(method.parameterTypes, args)
        } ?: clazz.declaredMethods.firstOrNull { method ->
            method.name == methodName && parametersMatch(method.parameterTypes, args)
        }
        if (match == null) {
            log(
                Log.ERROR,
                TAG,
                "Official blur method missing: ${clazz.name}.$methodName args=${args.size}",
                null,
            )
            return null
        }
        match.isAccessible = true
        methods[key] = match
        return match
    }

    private fun parametersMatch(types: Array<Class<*>>, args: Array<out Any?>): Boolean {
        if (types.size != args.size) return false
        types.indices.forEach { index ->
            val arg = args[index] ?: return@forEach
            val type = types[index]
            if (type.isInstance(arg)) return@forEach
            if (type.isPrimitive && boxedMatches(type, arg)) return@forEach
            return false
        }
        return true
    }

    private fun boxedMatches(primitive: Class<*>, arg: Any): Boolean {
        return when (primitive) {
            java.lang.Boolean.TYPE -> arg is Boolean
            java.lang.Integer.TYPE -> arg is Int
            java.lang.Float.TYPE -> arg is Float
            else -> false
        }
    }

    private fun loadClass(name: String): Class<*>? {
        classes[name]?.let { return it }
        return try {
            pluginClassLoader.loadClass(name).also { classes[name] = it }
        } catch (error: ClassNotFoundException) {
            log(Log.ERROR, TAG, "Official blur class missing: $name", error)
            null
        }
    }

    private data class MethodKey(
        val clazz: Class<*>,
        val name: String,
        val arity: Int,
    )

    private companion object {
        const val TAG = "SoundMan.SystemUi"
        val FAILED = Any()

        const val MI_BLUR_COMPAT = "miui.systemui.util.MiBlurCompat"
        const val VOLUME_UTIL = "com.android.systemui.miui.volume.Util"
        const val RINGER_BUTTON_RES = "com.android.systemui.miui.volume.RingerButtonRes"
        const val MIUI_COLOR_BLEND_TOKEN = "miui.systemui.util.MiuiColorBlendToken"
        const val BACKDROP_BLUR_VIEW = "com.miui.blur.sdk.backdrop.a"
        const val FIELD_INSTANCE = "INSTANCE"
        const val METHOD_THEME_BLUR_OPENED = "getBackgroundBlurOpenedInDefaultTheme"
        const val METHOD_SET_ROUND_RECT = "setRoundRect"
        const val METHOD_SET_MI_VIEW_BLUR_AND_BLEND = "setMiViewBlurAndBlendColor"
        const val METHOD_GET_BUTTON_BG_BLEND = "getButtonBgBlendColor"
        const val METHOD_GET_RINGER_BG_OFF = "getRINGER_BG_OFF"
        const val METHOD_SET_BLUR_ENABLED = "setBlurEnabled"
        const val METHOD_SET_CORNER_RADIUS = "setCornerRadius"
    }
}
