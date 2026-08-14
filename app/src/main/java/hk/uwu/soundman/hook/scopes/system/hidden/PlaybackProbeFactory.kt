package hk.uwu.soundman.hook.scopes.system.hidden

import hk.uwu.soundman.hook.scopes.system.hidden.PlaybackProbeFactory.current


/**
 * 选择当前启用的播放探测实现。
 *
 * 动机：媒体过滤路径与旧的 `isActive` 全量路径并存。改 [current] 即可切回，
 * 不改 `SystemAudioRuntime` 或 IPC。
 */
object PlaybackProbeFactory {
    /** 当前快照使用的探测实现。 */
    val current: Kind = Kind.MEDIA_FILTERED

    enum class Kind {
        /** 旧路径：`isActive()` 且 uid>=0，不过滤 usage / 系统 uid。 */
        LEGACY_ACTIVE,

        /** 新路径：对齐 MiSound，只保留媒体 STARTED 应用。 */
        MEDIA_FILTERED,
    }

    /**
     * 按 [current] 构造探测实现。
     *
     * @param access uid / 可选 piid / IPlayer
     * @param mediaAccess `getPlayerState` / AudioAttributes；仅媒体路径使用
     * @param packageNameForUid uid 到包名；仅媒体路径用来排除壁纸
     * @param logError 单条配置失败时的日志
     */
    fun create(
        access: PlaybackConfigurationAccess,
        mediaAccess: MediaPlaybackAccess,
        packageNameForUid: (uid: Int) -> String?,
        logError: (message: String, throwable: Throwable) -> Unit,
    ): PlaybackProbe = create(current, access, mediaAccess, packageNameForUid, logError)

    /**
     * 按指定种类构造探测实现，供单测覆盖两条路径。
     */
    fun create(
        kind: Kind,
        access: PlaybackConfigurationAccess,
        mediaAccess: MediaPlaybackAccess,
        packageNameForUid: (uid: Int) -> String?,
        logError: (message: String, throwable: Throwable) -> Unit,
    ): PlaybackProbe = when (kind) {
        Kind.LEGACY_ACTIVE -> ActivePlaybackProbe(access, logError)
        Kind.MEDIA_FILTERED -> MediaPlaybackProbe(access, mediaAccess, packageNameForUid, logError)
    }
}
