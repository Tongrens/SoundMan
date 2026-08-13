package hk.uwu.soundman.ui

import hk.uwu.soundman.data.AudioDeviceScan
import hk.uwu.soundman.model.AppAudioRule
import hk.uwu.soundman.model.AudioDeviceIdentity
import hk.uwu.soundman.model.AudioOutputDevice
import hk.uwu.soundman.model.OutputDeviceType
import hk.uwu.soundman.model.OutputTarget

/**
 * 设备页一行的语义分类。
 *
 * 动机：跟随系统、已连接设备和已断开外设必须分开建模，
 * 不能再把 FollowSystem 画成本机。
 */
enum class DevicePageRowKind {
    /** 跟随系统当前输出，不建立 UID 亲和性。 */
    FOLLOW_SYSTEM,

    /** 当前扫描到的已连接设备，包括本机扬声器。 */
    DEVICE,

    /** 规则仍绑定但扫描里已经没有的外设。 */
    DISCONNECTED,
}

/**
 * 设备页的一行展示数据。
 *
 * 动机：DevicePage 只渲染这份结果，不再自己 filter BUILT_IN 或把 FollowSystem 标成本机。
 *
 * @param kind 行类型
 * @param name 展示名；本机固定为调用方传入的「本机」文案
 * @param type 设备类型；跟随系统没有对应类型
 * @param selected 当前规则的生效目标是否落在这一行
 * @param enabled 断开行不可点
 * @param clickTarget 点击后要写入规则的目标；断开行为 null
 * @param key LazyColumn 稳定键
 */
data class DevicePageRow(
    val kind: DevicePageRowKind,
    val name: String,
    val type: OutputDeviceType?,
    val selected: Boolean,
    val enabled: Boolean,
    val clickTarget: OutputTarget?,
    val key: String,
) {
    init {
        require(name.isNotBlank()) { "device row name must not be blank" }
        require(key.isNotBlank()) { "device row key must not be blank" }
        require(enabled || kind == DevicePageRowKind.DISCONNECTED) {
            "only disconnected rows may be disabled"
        }
        require((clickTarget == null) == (kind == DevicePageRowKind.DISCONNECTED)) {
            "disconnected rows have no click target; other rows must have one"
        }
    }
}

/**
 * 按扫描结果和规则生成设备页行。
 *
 * 动机：选中态、本机目标和断开行必须是可单测的纯逻辑，
 * 不能再散落在 Composable 里用错标签。
 * 跟随系统始终第一行；本机只来自扫描里的 BUILT_IN，展示名不用 Speaker/Earpiece。
 */
class DevicePageRows {
    /**
     * 按「跟随系统 → 本机 → 已连接外设 → 断开外设」生成行。
     *
     * @param scan 当前宿主扫描到的输出设备
     * @param rule 该应用已保存规则
     * @param followSystemName `output_follow_system` 文案
     * @param builtinName `output_device_builtin` 文案，本机固定用它而不是 Speaker/Earpiece
     */
    fun build(
        scan: AudioDeviceScan,
        rule: AppAudioRule,
        followSystemName: String,
        builtinName: String,
    ): List<DevicePageRow> {
        require(followSystemName.isNotBlank()) { "followSystemName must not be blank" }
        require(builtinName.isNotBlank()) { "builtinName must not be blank" }

        val builtin = scan.devices.firstOrNull { device -> device.type == OutputDeviceType.BUILT_IN }
        val peripherals = scan.devices.filter { device -> device.type != OutputDeviceType.BUILT_IN }
        val boundDevice = rule.outputTarget as? OutputTarget.Device
        val effectiveDevice = rule.effectiveOutputTarget as? OutputTarget.Device
        val disconnected = boundDevice?.takeIf { device ->
            device.type != OutputDeviceType.BUILT_IN && peripherals.none { matches(device, it) }
        }

        val rows = ArrayList<DevicePageRow>(2 + peripherals.size + if (disconnected == null) 0 else 1)
        rows += DevicePageRow(
            kind = DevicePageRowKind.FOLLOW_SYSTEM,
            name = followSystemName,
            type = null,
            selected = rule.effectiveOutputTarget == OutputTarget.FollowSystem,
            enabled = true,
            clickTarget = OutputTarget.FollowSystem,
            key = "follow_system",
        )
        if (builtin != null) {
            rows += DevicePageRow(
                kind = DevicePageRowKind.DEVICE,
                name = builtinName,
                type = OutputDeviceType.BUILT_IN,
                selected = effectiveDevice != null && matches(effectiveDevice, builtin),
                enabled = true,
                clickTarget = builtin.target,
                key = deviceKey("device", builtin.candidates),
            )
        }
        peripherals.forEach { device ->
            rows += DevicePageRow(
                kind = DevicePageRowKind.DEVICE,
                name = displayName(device.productName, device.type),
                type = device.type,
                selected = effectiveDevice != null && matches(effectiveDevice, device),
                enabled = true,
                clickTarget = device.target,
                key = deviceKey("device", device.candidates),
            )
        }
        if (disconnected != null) {
            rows += DevicePageRow(
                kind = DevicePageRowKind.DISCONNECTED,
                name = displayName(disconnected.productName, disconnected.type),
                type = disconnected.type,
                selected = false,
                enabled = false,
                clickTarget = null,
                key = deviceKey("disconnected", disconnected.candidates),
            )
        }
        return rows
    }

    private fun matches(target: OutputTarget.Device, device: AudioOutputDevice): Boolean {
        return target.candidates.any { candidate -> device.candidates.contains(candidate) }
    }

    private fun displayName(productName: String, type: OutputDeviceType): String =
        productName.ifBlank { type.name }

    private fun deviceKey(prefix: String, candidates: List<AudioDeviceIdentity>): String =
        prefix + ":" + candidates.joinToString(";") { candidate ->
            "${candidate.internalType}:${candidate.address}"
        }
}
