package hk.uwu.soundman.log

import android.util.Log
import hk.uwu.soundman.BuildConfig

/**
 * 模块 App 进程日志。
 *
 * 动机：YukiHook 的 YLog 只在 Hook 环境里适用；普通进程落到 [android.util.Log]。
 * 同样只在 [BuildConfig.DEBUG] 时输出。
 */
object AppLog {
    private const val TAG = "SoundMan"

    /**
     * Release 构建必须静默。
     */
    internal fun shouldEmit(debugBuild: Boolean): Boolean = debugBuild

    /** 输出 debug 文本。 */
    fun debug(message: Any?) {
        if (!shouldEmit(BuildConfig.DEBUG)) return
        Log.d(TAG, message.toString())
    }

    /** 输出 info 文本。 */
    fun info(message: Any?) {
        if (!shouldEmit(BuildConfig.DEBUG)) return
        Log.i(TAG, message.toString())
    }

    /** 输出 warn 文本或异常。 */
    fun warn(message: Any?) {
        if (!shouldEmit(BuildConfig.DEBUG)) return
        if (message is Throwable) Log.w(TAG, message.message.orEmpty(), message)
        else Log.w(TAG, message.toString())
    }

    /** 输出 error 文本或异常。 */
    fun error(message: Any?) {
        if (!shouldEmit(BuildConfig.DEBUG)) return
        if (message is Throwable) Log.e(TAG, message.message.orEmpty(), message)
        else Log.e(TAG, message.toString())
    }

    /** 输出带异常对象的 debug 文本。 */
    fun debug(message: Any?, throwable: Throwable?) {
        if (!shouldEmit(BuildConfig.DEBUG)) return
        Log.d(TAG, message.toString(), throwable)
    }

    /** 输出带异常对象的 info 文本。 */
    fun info(message: Any?, throwable: Throwable?) {
        if (!shouldEmit(BuildConfig.DEBUG)) return
        Log.i(TAG, message.toString(), throwable)
    }

    /** 输出带异常对象的 warn 文本。 */
    fun warn(message: Any?, throwable: Throwable?) {
        if (!shouldEmit(BuildConfig.DEBUG)) return
        Log.w(TAG, message.toString(), throwable)
    }

    /** 输出带异常对象的 error 文本。 */
    fun error(message: Any?, throwable: Throwable?) {
        if (!shouldEmit(BuildConfig.DEBUG)) return
        Log.e(TAG, message.toString(), throwable)
    }
}
