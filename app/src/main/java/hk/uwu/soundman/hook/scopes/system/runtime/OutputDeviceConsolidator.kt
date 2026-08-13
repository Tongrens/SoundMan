package hk.uwu.soundman.hook.scopes.system.runtime

import hk.uwu.soundman.model.AudioOutputDevice
import hk.uwu.soundman.model.OutputDeviceType

/**
 * 把扫描到的输出设备按物理设备收成一台。
 *
 * 动机：`AudioManager.getDevices` 会把本机扬声器/听筒、同一蓝牙的 A2DP/BLE
 * 拆成多条。只按 identity 去重留不下这些成对类型，面板会显示两台本机或两台蓝牙。
 * Android 对同一台外设会给出多条 `AudioDeviceInfo`（A2DP/BLE/SCO、
 * USB device/headset、有线 headset/headphones）。地址和展示名常常对不齐；
 * 这里用并查集：同类型下地址相交或真实产品名相同就合并。合成名 `type:数字` 不参与匹配。
 */
class OutputDeviceConsolidator {
    /**
     * 按设备类型与地址合并扫描结果。
     *
     * @param devices mapper 产出的原始设备，允许同一物理设备有多条
     * @return 合并后的设备；组顺序保持首次出现顺序，candidates 至少 1 个
     */
    fun consolidate(devices: List<AudioOutputDevice>): List<AudioOutputDevice> {
        if (devices.isEmpty()) return emptyList()
        val parent = IntArray(devices.size) { it }
        fun find(index: Int): Int {
            var current = index
            while (parent[current] != current) {
                parent[current] = parent[parent[current]]
                current = parent[current]
            }
            return current
        }
        fun union(left: Int, right: Int) {
            val leftRoot = find(left)
            val rightRoot = find(right)
            if (leftRoot != rightRoot) parent[rightRoot] = leftRoot
        }
        for (i in devices.indices) {
            for (j in i + 1 until devices.size) {
                if (samePhysicalDevice(devices[i], devices[j])) union(i, j)
            }
        }
        val groups = LinkedHashMap<Int, MutableList<AudioOutputDevice>>()
        devices.forEachIndexed { index, device ->
            groups.getOrPut(find(index)) { ArrayList() }.add(device)
        }
        return groups.values.map(::mergeGroup)
    }

    private fun mergeGroup(group: List<AudioOutputDevice>): AudioOutputDevice {
        val type = group.first().type
        val candidates = group.flatMap(AudioOutputDevice::candidates).distinct()
        require(candidates.isNotEmpty()) { "consolidated device requires at least one route candidate" }
        val productName = group.map(AudioOutputDevice::productName).firstOrNull(::isRealProductName).orEmpty()
        return AudioOutputDevice(type, candidates, productName)
    }

    private fun samePhysicalDevice(left: AudioOutputDevice, right: AudioOutputDevice): Boolean {
        if (left.type != right.type) return false
        if (left.type == OutputDeviceType.BUILT_IN) return true
        val leftAddresses = addressesOf(left)
        val rightAddresses = addressesOf(right)
        if (leftAddresses.isNotEmpty() && rightAddresses.any(leftAddresses::contains)) return true
        val leftNames = namesOf(left)
        val rightNames = namesOf(right)
        return leftNames.isNotEmpty() && rightNames.any(leftNames::contains)
    }

    private fun addressesOf(device: AudioOutputDevice): Set<String> =
        device.candidates.map { normalizeAddress(it.address) }.filter { it.isNotEmpty() }.toSet()

    private fun namesOf(device: AudioOutputDevice): Set<String> =
        listOfNotNull(normalizeProductName(device.productName)).toSet()

    private fun normalizeAddress(address: String): String =
        address.filter(Char::isLetterOrDigit).uppercase()

    private fun normalizeProductName(name: String): String? {
        if (!isRealProductName(name)) return null
        var normalized = name.lowercase().replace(WHITESPACE, " ").trim()
        PRODUCT_NAME_SUFFIXES.forEach { suffix ->
            if (normalized.endsWith(suffix)) {
                normalized = normalized.removeSuffix(suffix).trim()
            }
        }
        return normalized.takeIf { it.isNotEmpty() }
    }

    private fun isRealProductName(name: String): Boolean {
        if (name.isBlank()) return false
        return !SYNTHETIC_TYPE_NAME.matches(name)
    }

    private companion object {
        val WHITESPACE = Regex("\\s+")
        val SYNTHETIC_TYPE_NAME = Regex("^type:\\d+$")
        val PRODUCT_NAME_SUFFIXES = listOf(" le", " ble", " le-audio", " headset", " headphones")
    }
}
