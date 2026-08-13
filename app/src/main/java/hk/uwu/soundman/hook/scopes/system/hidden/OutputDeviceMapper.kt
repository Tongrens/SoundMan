package hk.uwu.soundman.hook.scopes.system.hidden

import android.media.AudioDeviceInfo
import hk.uwu.soundman.model.AudioDeviceIdentity
import hk.uwu.soundman.model.AudioOutputDevice
import hk.uwu.soundman.model.OutputDeviceType

/**
 * 把公开 `AudioDeviceInfo` 字段映射成快照可用的输出设备。
 *
 * 动机：内置扬声器/听筒的 address 经常是空字符串。旧逻辑无条件丢空地址，
 * 导致 `scanOutputDevices` 得到 0 台设备。AudioSystem 接受内置设备的空地址，
 * 映射必须把这类设备留下来，同时不能给蓝牙/USB 等外设编造地址。
 *
 * [outputDeviceType] 把公开 `TYPE_*` 映射为隐藏 `DEVICE_OUT_*`；未知类型返回 null。
 * 生产传入 [HiddenAudioSystem.outputDeviceType]，单测传入固定表。
 */
class OutputDeviceMapper(
    private val outputDeviceType: (publicType: Int) -> Int?,
) {
    constructor(audioSystem: HiddenAudioSystem) : this(audioSystem::outputDeviceType)

    /**
     * 映射一台公开输出设备。
     *
     * @param publicType 公开 `AudioDeviceInfo.TYPE_*`
     * @param address 设备地址；内置设备允许空字符串
     * @param productName 展示名，不参与身份匹配
     * @return 可路由设备；未知公开类型、或非内置且地址为空时返回 null
     */
    fun map(publicType: Int, address: String, productName: String): AudioOutputDevice? {
        val internalType = outputDeviceType(publicType) ?: return null
        val resolvedAddress = when {
            address.isNotEmpty() -> address
            publicType == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER ||
                publicType == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> ""
            else -> return null
        }
        val type = when (publicType) {
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> OutputDeviceType.BUILT_IN
            AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> OutputDeviceType.WIRED_HEADSET
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER,
            AudioDeviceInfo.TYPE_BLE_BROADCAST -> OutputDeviceType.BLUETOOTH
            AudioDeviceInfo.TYPE_USB_ACCESSORY, AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_HEADSET -> OutputDeviceType.USB
            else -> OutputDeviceType.OTHER
        }
        return AudioOutputDevice(
            type,
            listOf(AudioDeviceIdentity(internalType, resolvedAddress)),
            productName,
        )
    }
}
