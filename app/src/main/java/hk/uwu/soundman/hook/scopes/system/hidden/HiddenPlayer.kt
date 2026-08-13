package hk.uwu.soundman.hook.scopes.system.hidden

import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

/**
 * 隐藏 `android.media.IPlayer` 的反射访问面。
 *
 * 动机：AudioService.trackPlayer 拿到的是 framework IPlayer，公开 SDK 没有这个类型。
 * 业务层通过 [setVolume] 调音。把运行时 IPlayer / player proxy 包装成本类。
 * 构造期解析方法，缺 `setVolume(float)` 立即失败。
 *
 * @param player 具备 `setVolume(float)` 的运行时播放器
 */
class HiddenPlayer(player: Any) {
    private val instance: Any = player
    private val setVolume: Method = resolveSetVolume(player.javaClass)
    private val pause: Method? = resolveNoArg(player.javaClass, METHOD_PAUSE, METHOD_TRACK_PAUSE)
    private val start: Method? = resolveNoArg(player.javaClass, METHOD_START, METHOD_TRACK_START)

    /**
     * 设置该播放器的音量倍率。
     *
     * 动机：SystemAudioRuntime 在规则生效或临时调音时调用 IPlayer.setVolume(float)。
     *
     * @param volume 0f..1f 的倍率；具体范围由 framework 解释
     */
    fun setVolume(volume: Float) {
        try {
            setVolume.invoke(instance, volume)
        } catch (error: InvocationTargetException) {
            throw error.targetException ?: error
        }
    }

    /**
     * 暂停再启动，迫使当前 Track 按新策略重新选输出设备。
     *
     * 动机：部分 ROM 在改道后仍需要 kick 一下已在播的 IPlayer。
     *
     * @return 是否成功发出 pause+start
     */
    fun restartForReroute(): Boolean {
        val pauseMethod = pause
        val startMethod = start
        if (pauseMethod == null || startMethod == null) return false
        try {
            pauseMethod.invoke(instance)
            startMethod.invoke(instance)
            return true
        } catch (error: InvocationTargetException) {
            throw error.targetException ?: error
        }
    }

    private companion object {
        const val METHOD_SET_VOLUME = "setVolume"
        const val METHOD_PAUSE = "pause"
        const val METHOD_START = "start"
        const val METHOD_TRACK_PAUSE = "trackPause"
        const val METHOD_TRACK_START = "trackStart"

        fun resolveNoArg(playerClass: Class<*>, vararg names: String): Method? {
            names.forEach { name ->
                val method = try {
                    playerClass.getMethod(name)
                } catch (publicMissing: NoSuchMethodException) {
                    try {
                        playerClass.getDeclaredMethod(name)
                    } catch (declaredMissing: NoSuchMethodException) {
                        null
                    }
                }
                if (method != null) {
                    method.isAccessible = true
                    return method
                }
            }
            return null
        }

        fun resolveSetVolume(playerClass: Class<*>): Method {
            val parameterTypes = arrayOf(Float::class.javaPrimitiveType!!)
            val method = try {
                playerClass.getMethod(METHOD_SET_VOLUME, *parameterTypes)
            } catch (publicMissing: NoSuchMethodException) {
                try {
                    playerClass.getDeclaredMethod(METHOD_SET_VOLUME, *parameterTypes)
                } catch (declaredMissing: NoSuchMethodException) {
                    throw IllegalStateException(
                        "Missing method $METHOD_SET_VOLUME(float) on ${playerClass.name}",
                        declaredMissing,
                    )
                }
            }
            method.isAccessible = true
            return method
        }
    }
}
