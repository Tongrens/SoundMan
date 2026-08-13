package hk.uwu.soundman.hook.scopes.system.runtime

import hk.uwu.soundman.model.AudioDeviceIdentity

/**
 * 一次绑定所需的已连接候选，以及与设备数组对齐的身份。
 *
 * 动机：蓝牙 A2DP/BLE 必须整组绑到同一台物理设备，types 与 addresses 下标必须对齐。
 *
 * @param identities 当前已连接且保持原候选顺序的身份
 */
data class ConnectedRouteAffinity(
    val identities: List<AudioDeviceIdentity>,
) {
    init {
        require(identities.isNotEmpty()) { "connected affinity requires at least one candidate" }
    }

    /** 与 [identities] 对齐的隐藏 `DEVICE_OUT_*`。 */
    val deviceIds: IntArray
        get() = IntArray(identities.size) { index -> identities[index].internalType }

    /** 与 [deviceIds] 对齐的设备地址；本机空地址合法。 */
    val deviceAddresses: Array<String>
        get() = Array(identities.size) { index -> identities[index].address }
}

/**
 * 从设备目标中收集当前已连接的改道候选。
 *
 * 动机：路由层先筛连接状态，再提交目标设备，避免把已断开的蓝牙写进 Settings。
 * 只保留当前已连接的候选，并维持原顺序。
 */
class ConnectedRouteCandidates {
    /**
     * 筛出已连接候选。全部未连接时返回 null，禁止提交空数组。
     *
     * @param candidates 规则里保存的全部候选
     * @param isConnected 该身份当前是否 `DEVICE_STATE_AVAILABLE`
     */
    fun collect(
        candidates: List<AudioDeviceIdentity>,
        isConnected: (AudioDeviceIdentity) -> Boolean,
    ): ConnectedRouteAffinity? {
        require(candidates.isNotEmpty()) { "device target requires at least one route candidate" }
        val connected = candidates.filter(isConnected)
        return if (connected.isEmpty()) null else ConnectedRouteAffinity(connected)
    }
}
