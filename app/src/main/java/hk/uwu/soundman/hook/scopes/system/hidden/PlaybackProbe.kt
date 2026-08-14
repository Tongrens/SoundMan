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
 * 动机：宿主快照只依赖本接口。旧的全量 `isActive` 探测与 MiSound 媒体过滤探测并存，
 * 由 [PlaybackProbeFactory] 选择当前实现，切回时不改 Runtime。
 */
interface PlaybackProbe {
    /**
     * 读取公开 `getActivePlaybackConfigurations()` 并过滤出当前要展示的播放。
     *
     * @param audioManager system_server 内的 AudioManager
     */
    fun probe(audioManager: AudioManager): List<ProbedPlayback>
}
