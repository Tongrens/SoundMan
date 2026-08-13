package hk.uwu.soundman.hook.scopes.system.runtime

import hk.uwu.soundman.model.AudioDeviceIdentity
import hk.uwu.soundman.model.OutputTarget

/**
 * 路由调试文案。
 *
 * 动机：播放中切设备是否立刻绑上，只能靠 logcat 对照连接状态、亲和性返回码和
 * 当前 STARTED 播放器数量，不能靠界面勾选。
 */
object RouteDebug {
    /**
     * 描述一次切设备命令的目标。
     *
     * @param target 规则里的输出目标
     */
    fun describeTarget(target: OutputTarget): String = when (target) {
        OutputTarget.FollowSystem -> "FollowSystem"
        is OutputTarget.Device ->
            "Device(type=${target.type} name=${target.productName} candidates=${describeCandidates(target.candidates)})"
    }

    /**
     * 描述一组候选的 type:address。
     *
     * @param candidates 亲和性候选
     */
    fun describeCandidates(candidates: List<AudioDeviceIdentity>): String =
        candidates.joinToString(prefix = "[", postfix = "]") { candidate ->
            "${candidate.internalType}:${candidate.address.ifEmpty { "<empty>" }}"
        }

    /**
     * 描述单个候选的连接检查结果。
     *
     * @param candidate 候选身份
     * @param state `getDeviceConnectionState` 返回值
     * @param available `DEVICE_STATE_AVAILABLE`
     */
    fun describeConnection(candidate: AudioDeviceIdentity, state: Int, available: Int): String {
        val connected = state == available
        return "type=${candidate.internalType} address=${candidate.address.ifEmpty { "<empty>" }} " +
            "state=$state available=$available connected=$connected"
    }

    /**
     * 描述本次绑定面对的播放器数量。
     *
     * @param startedCount 该 uid 当前 STARTED 播放器数
     * @param trackedCount 该 uid 已跟踪播放器数
     */
    fun describePlayers(startedCount: Int, trackedCount: Int): String =
        "started=$startedCount tracked=$trackedCount live=${startedCount > 0}"
}
