package hk.uwu.soundman.hook.scopes.system.runtime

import hk.uwu.soundman.model.AudioDeviceIdentity
import hk.uwu.soundman.model.OutputDeviceType

/**
 * 本机扬声器候选筛选。
 *
 * 动机：扫描会把听筒 `DEVICE_OUT_EARPIECE` 和扬声器 `DEVICE_OUT_SPEAKER`
 * 收成一台 BUILT_IN。用户点「本机」要的是外放，不能把听筒一起绑进亲和性，
 * 否则策略仍可能把媒体送到耳机。
 */
object BuiltinSpeakerRoute {
    /**
     * 本机只留扬声器候选；其它设备类型原样返回。
     *
     * @param type 规则设备类型
     * @param candidates 扫描/规则里的全部候选
     * @param speakerInternalType `AudioSystem.DEVICE_OUT_SPEAKER`
     * @return 本机时只有扬声器；没有扬声器则空列表
     */
    fun pick(
        type: OutputDeviceType,
        candidates: List<AudioDeviceIdentity>,
        speakerInternalType: Int,
    ): List<AudioDeviceIdentity> {
        require(candidates.isNotEmpty()) { "device target requires at least one route candidate" }
        if (type != OutputDeviceType.BUILT_IN) return candidates
        return candidates.filter { candidate -> candidate.internalType == speakerInternalType }
    }
}
