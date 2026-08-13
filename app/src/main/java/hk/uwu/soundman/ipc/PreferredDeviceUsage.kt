package hk.uwu.soundman.ipc

/**
 * 按目标设备动态分配 AudioAttributes.usage，让不同设备上的应用不再挤进同一条 MUSIC Mix。
 *
 * 小米 `key_ignore_music_focus_req` 只把 USAGE_MEDIA / USAGE_NOTIFICATION 并进默认音乐输出。
 * 同一设备上的 uid 共用一个 usage；不同设备从 GAME / ASSISTANT / NAVIGATION / ACCESSIBILITY 里依次取。
 * FollowSystem 保持 USAGE_MEDIA，不改写。
 */
object PreferredDeviceUsage {
    const val USAGE_MEDIA = 1
    const val USAGE_GAME = 14
    const val USAGE_ASSISTANT = 16
    const val USAGE_NAVIGATION = 12
    const val USAGE_ACCESSIBILITY = 11

    private val POOL = intArrayOf(USAGE_GAME, USAGE_ASSISTANT, USAGE_NAVIGATION, USAGE_ACCESSIBILITY)

    /**
     * @param hints 当前全部 uid 的改道提示
     * @return uid → usage
     */
    fun allocate(hints: List<PreferredDeviceSync.RouteHint>): Map<Int, Int> {
        val groupUsage = LinkedHashMap<String, Int>()
        var next = 0
        return hints.sortedBy(PreferredDeviceSync.RouteHint::uid).associate { hint ->
            val usage = if (hint.followSystem) {
                USAGE_MEDIA
            } else {
                val key = "${hint.publicType}|${hint.address}"
                groupUsage.getOrPut(key) {
                    check(next < POOL.size) { "too many distinct forced devices: ${next + 1}" }
                    POOL[next++]
                }
            }
            hint.uid to usage
        }
    }

    /** 把分配结果写回 hint。 */
    fun withAllocatedUsages(hints: List<PreferredDeviceSync.RouteHint>): List<PreferredDeviceSync.RouteHint> {
        val usages = allocate(hints)
        return hints.map { hint ->
            hint.copy(usage = usages[hint.uid] ?: USAGE_MEDIA)
        }
    }

    /** 是否需要在构造 Track 时改写 attributes。 */
    fun shouldRewrite(usage: Int): Boolean = usage != USAGE_MEDIA
}
