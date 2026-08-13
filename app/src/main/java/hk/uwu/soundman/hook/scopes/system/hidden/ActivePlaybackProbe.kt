package hk.uwu.soundman.hook.scopes.system.hidden

import android.media.AudioManager

/**
 * 一条探测到的活跃播放。
 *
 * @param uid 客户端 uid，始终 >= 0
 * @param piid PlayerInterfaceId；ROM 缺方法时为 null
 * @param player `getPlayerProxy` 包装结果；缺方法或 proxy 为 null 时为 null
 */
data class ProbedPlayback(
    val uid: Int,
    val piid: Int?,
    val player: HiddenPlayer?,
)

/**
 * 从 AudioManager 探测当前活跃播放。
 *
 * 动机：模块晚于开播安装时，已在播的 player 不会再走 `trackPlayer`，
 * hook 记录为空。快照必须仍能列出 AudioManager 报告的活跃 uid。
 * 整表读取走公开 `AudioManager.getActivePlaybackConfigurations()`。
 * 单条配置的必需字段失败会打日志并跳过该条；可选 piid / IPlayer 失败时仍保留 uid。
 */
class ActivePlaybackProbe(
    private val access: PlaybackConfigurationAccess,
    private val logError: (message: String, throwable: Throwable) -> Unit,
) {
    /**
     * 读取公开 `getActivePlaybackConfigurations()`，只保留 `isActive` 且 uid>=0 的配置。
     *
     * @param audioManager system_server 内的 AudioManager
     * @return 探测到的活跃播放；单条配置解析失败时跳过该条，其它配置继续
     */
    fun probe(audioManager: AudioManager): List<ProbedPlayback> =
        probeConfigurations(audioManager.activePlaybackConfigurations)

    /**
     * 对任意配置列表走同一套过滤与访问逻辑，供生产 `AudioPlaybackConfiguration` 与单测假类共用。
     *
     * @param configs `getActivePlaybackConfigurations()` 的元素，或等价假对象
     */
    fun probeConfigurations(configs: List<*>): List<ProbedPlayback> {
        val probed = ArrayList<ProbedPlayback>(configs.size)
        configs.forEach { config ->
            if (config == null) {
                logError(
                    "[snapshot] skipped null playback configuration",
                    NullPointerException("playback configuration is null"),
                )
                return@forEach
            }
            val uid = readRequired(config, "getClientUid") { access.clientUid(config) } ?: return@forEach
            val active = readRequired(config, "isActive") { access.isActive(config) } ?: return@forEach
            if (!active || uid < 0) return@forEach
            val piid = readOptional(config, "getPlayerInterfaceId") { access.playerInterfaceId(config) }
            val player = readOptional(config, "getPlayerProxy") { access.player(config) }
            probed += ProbedPlayback(uid, piid, player)
        }
        return probed
    }

    private fun <T> readRequired(config: Any, methodName: String, read: () -> T): T? = try {
        read()
    } catch (throwable: Throwable) {
        logError(
            "[snapshot] failed to read $methodName from ${config.javaClass.name}",
            throwable,
        )
        null
    }

    private fun <T> readOptional(config: Any, methodName: String, read: () -> T): T? = try {
        read()
    } catch (throwable: Throwable) {
        logError(
            "[snapshot] failed to read optional $methodName from ${config.javaClass.name}",
            throwable,
        )
        null
    }
}
