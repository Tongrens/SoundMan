package hk.uwu.soundman.model

import android.graphics.drawable.Drawable

/**
 * 可供规则显式绑定的输出设备类型。
 */
enum class OutputDeviceType {
    BUILT_IN,
    WIRED_HEADSET,
    BLUETOOTH,
    USB,
    OTHER,
}

/** AudioSystem 输出设备的稳定匹配身份；展示名称不参与连接状态或断开判断。 */
data class AudioDeviceIdentity(
    val internalType: Int,
    val address: String,
)

/**
 * 单个应用的输出目标。跟随系统不会建立 UID 设备亲和性；Device 以 AudioSystem internal type 与地址持久化身份。
 */
sealed interface OutputTarget {
    data object FollowSystem : OutputTarget

    data class Device(
        val type: OutputDeviceType,
        val candidates: List<AudioDeviceIdentity>,
        val productName: String,
    ) : OutputTarget {
        init {
            require(candidates.isNotEmpty()) { "device target requires at least one route candidate" }
        }

        val identity: AudioDeviceIdentity
            get() = candidates.first()
    }
}

/**
 * 以包名为键的音频规则。100 表示不额外衰减，取值始终限制在 0..100。
 */
data class AppAudioRule(
    val packageName: String,
    val uid: Int,
    val volumePercent: Int,
    val outputTarget: OutputTarget,
    val revision: Long,
    val followsSystemAfterDisconnect: Boolean = false,
) {
    init {
        require(packageName.isNotBlank()) { "packageName must not be blank" }
        require(uid >= 0) { "uid must be non-negative" }
        require(volumePercent in 0..100) { "volumePercent must be in 0..100" }
        require(revision >= 0L) { "revision must not be negative" }
        require(!followsSystemAfterDisconnect || outputTarget is OutputTarget.Device) {
            "disconnect fallback requires a fixed device target"
        }
    }

    val effectiveOutputTarget: OutputTarget
        get() = if (followsSystemAfterDisconnect) OutputTarget.FollowSystem else outputTarget

    val multiplier: Float
        get() = volumePercent / 100f
}

/**
 * 面板中的可调节应用。图标由 PackageManager 直接提供，避免复制应用资源。
 */
data class AdjustableApp(
    val packageName: String,
    val label: String,
    val uid: Int,
    val icon: Drawable,
)

/**
 * 当前系统公开的物理输出设备。internalType 是由明确映射得到的 AudioSystem 输出类型。
 */
data class AudioOutputDevice(
    val type: OutputDeviceType,
    val candidates: List<AudioDeviceIdentity>,
    val productName: String,
) {
    init {
        require(candidates.isNotEmpty()) { "audio output device requires route candidates" }
    }

    val identity: AudioDeviceIdentity
        get() = candidates.first()

    val target: OutputTarget.Device
        get() = OutputTarget.Device(type, candidates, productName)
}
