package hk.uwu.soundman.hook.core

import android.util.Log

/**
 * Hook 与宿主共用的日志门面。
 *
 * 动机：去掉 libxposed / HookLogger 后，模块仍需要统一的 debug/info/warn/error 出口。
 * 这里直接落到 [android.util.Log]，tag 固定为 SoundMan。
 */
object YLog {
    private const val TAG = "SoundMan"

    /** 输出 debug 文本。 */
    fun debug(message: Any?) = Log.d(TAG, message.toString())

    /** 输出 info 文本。 */
    fun info(message: Any?) = Log.i(TAG, message.toString())

    /** 输出 warn 文本或异常。 */
    fun warn(message: Any?) {
        if (message is Throwable) Log.w(TAG, message.message.orEmpty(), message)
        else Log.w(TAG, message.toString())
    }

    /** 输出 error 文本或异常。 */
    fun error(message: Any?) {
        if (message is Throwable) Log.e(TAG, message.message.orEmpty(), message)
        else Log.e(TAG, message.toString())
    }

    /** 输出带异常对象的 debug 文本。 */
    fun debug(message: Any?, throwable: Throwable?) = Log.d(TAG, message.toString(), throwable)

    /** 输出带异常对象的 info 文本。 */
    fun info(message: Any?, throwable: Throwable?) = Log.i(TAG, message.toString(), throwable)

    /** 输出带异常对象的 warn 文本。 */
    fun warn(message: Any?, throwable: Throwable?) = Log.w(TAG, message.toString(), throwable)

    /** 输出带异常对象的 error 文本。 */
    fun error(message: Any?, throwable: Throwable?) = Log.e(TAG, message.toString(), throwable)
}
