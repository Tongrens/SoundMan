package hk.uwu.soundman.data

import hk.uwu.soundman.model.AudioOutputDevice

enum class AudioDeviceScanError { HOST_UNAVAILABLE }

data class AudioDeviceScan(
    val devices: List<AudioOutputDevice>,
    val error: AudioDeviceScanError?,
)

interface AudioDevicesSource {
    fun scan(): AudioDeviceScan
    fun observe(observer: (AudioDeviceScan) -> Unit): () -> Unit
}

/** App 端只消费 system_server 提供的候选快照，不加载或解析 framework 隐藏音频类型。 */
class HostAudioDevicesSource(private val hostSource: HostPlaybackSource) : AudioDevicesSource {
    override fun scan(): AudioDeviceScan = hostSource.currentDeviceScan()

    override fun observe(observer: (AudioDeviceScan) -> Unit): () -> Unit = hostSource.observeDevices(observer)
}
