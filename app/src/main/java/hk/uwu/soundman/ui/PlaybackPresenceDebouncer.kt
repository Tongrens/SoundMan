package hk.uwu.soundman.ui

/**
 * 稳定播放列表的出现与消失。
 *
 * 动机：切歌时 STARTED 会短暂掉到 0，快照立刻把列表推空再推回。
 * 新应用必须马上出现；消失的应用延迟移除，窗口内回来则取消。
 * 新出现的项立刻进入可见列表；消失的项在 [holdMillis] 内保留，窗口内回来则取消移除。
 */
class PlaybackPresenceDebouncer<T>(
    private val holdMillis: Long = DEFAULT_HOLD_MILLIS,
    private val keyOf: (T) -> String,
) {
    private val pendingRemovals = LinkedHashMap<String, Held<T>>()
    private var lastVisible = emptyList<T>()
    private var lastNowMillis: Long? = null

    init {
        require(holdMillis > 0L) { "holdMillis must be positive" }
    }

    /**
     * 根据最新快照计算当前应展示的列表。
     *
     * @param incoming 本轮快照中的项
     * @param nowMillis 单调时钟，单位毫秒
     * @return 立即显示的新项，加上仍在延迟窗口内的旧项
     */
    fun update(incoming: List<T>, nowMillis: Long): List<T> {
        require(nowMillis >= 0L) { "nowMillis must not be negative" }
        lastNowMillis?.let { previous ->
            require(nowMillis >= previous) { "nowMillis must be monotonic" }
        }
        lastNowMillis = nowMillis

        val incomingByKey = LinkedHashMap<String, T>()
        incoming.forEach { item ->
            val key = keyOf(item)
            require(key.isNotBlank()) { "playback item key must not be blank" }
            check(incomingByKey.put(key, item) == null) { "incoming playback items must have unique keys: $key" }
        }

        incomingByKey.keys.forEach { key -> pendingRemovals.remove(key) }

        lastVisible.forEach { item ->
            val key = keyOf(item)
            if (key !in incomingByKey && key !in pendingRemovals) {
                pendingRemovals[key] = Held(item, nowMillis + holdMillis)
            }
        }

        pendingRemovals.entries.removeAll { entry -> nowMillis >= entry.value.removeAtMillis }

        val visible = ArrayList<T>(incoming.size + pendingRemovals.size)
        visible.addAll(incoming)
        pendingRemovals.values.forEach { held ->
            if (keyOf(held.item) !in incomingByKey) {
                visible += held.item
            }
        }
        lastVisible = visible
        return visible
    }

    /**
     * 下一趟过期移除的时间点。没有待移除项时为 null。
     */
    fun nextDeadlineMillis(): Long? =
        pendingRemovals.values.minOfOrNull { held -> held.removeAtMillis }

    private data class Held<T>(val item: T, val removeAtMillis: Long)

    companion object {
        const val DEFAULT_HOLD_MILLIS = 450L
    }
}
