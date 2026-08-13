package hk.uwu.soundman.hook.scopes.system.runtime

/**
 * 合并 hook 已开始播放的 UID 与 AudioManager 探测到的活跃 UID。
 *
 * 动机：模块晚于开播安装时 hook 记录可能为空，快照仍必须列出正在播放的应用。
 * 探测到但还没有 STARTED 记录的 uid 以 count=1 进入快照，满足 PlaybackEntry 的正数约束。
 * 已有 STARTED 计数保留原值；只出现在探测结果里的 uid 记为 1。
 */
class SnapshotPlaybackMerge {
    /**
     * 计算快照要用的 uid → 播放器数量。
     *
     * @param startedCounts hook 记录里 `state == STARTED` 的 uid 计数
     * @param probedUids [hk.uwu.soundman.hook.scopes.system.hidden.ActivePlaybackProbe] 得到的 uid
     * @return uid 升序的计数表；探测到但无 STARTED 记录时 count 为 1
     */
    fun merge(startedCounts: Map<Int, Int>, probedUids: Set<Int>): Map<Int, Int> {
        val merged = LinkedHashMap<Int, Int>()
        (startedCounts.keys + probedUids).sorted().forEach { uid ->
            val started = startedCounts[uid] ?: 0
            merged[uid] = if (started > 0) started else 1
        }
        return merged
    }
}
