package hk.uwu.soundman.hook.scopes.system.runtime

import hk.uwu.soundman.model.AudioDeviceIdentity

/**
 * 蓝牙媒体路由候选筛选。
 *
 * 动机：扫描常带一条 SCO `00:00:00:00:00:00`。媒体应走 A2DP/BLE，
 * 把全 0 地址和 SCO 一起绑进去会让 `setUidDeviceAffinities` 失败或绑到通话通路。
 */
object BluetoothMediaRoute {
    /**
     * 去掉假地址，并在有 A2DP/BLE 时丢掉 SCO。
     *
     * @param candidates 蓝牙设备的全部候选
     * @param scoInternalType `DEVICE_OUT_BLUETOOTH_SCO`
     */
    fun pick(candidates: List<AudioDeviceIdentity>, scoInternalType: Int): List<AudioDeviceIdentity> {
        require(candidates.isNotEmpty()) { "bluetooth target requires at least one route candidate" }
        val usable = candidates.filter { candidate -> !isDummyAddress(candidate.address) }
        val media = usable.filter { candidate -> candidate.internalType != scoInternalType }
        return when {
            media.isNotEmpty() -> media
            usable.isNotEmpty() -> usable
            else -> candidates
        }
    }

    /**
     * 全 0 MAC 或空串不能作为媒体设备地址。
     */
    fun isDummyAddress(address: String): Boolean {
        val hex = address.filter(Char::isLetterOrDigit)
        return hex.isEmpty() || hex.all { digit -> digit == '0' }
    }
}
