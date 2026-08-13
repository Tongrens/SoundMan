package hk.uwu.soundman.hook.scopes.system.runtime

import hk.uwu.soundman.model.AudioDeviceIdentity
import hk.uwu.soundman.model.AudioOutputDevice
import hk.uwu.soundman.model.OutputDeviceType
import org.junit.Assert.assertEquals
import org.junit.Test

class OutputDeviceConsolidatorTest {
    private val consolidator = OutputDeviceConsolidator()

    @Test
    fun mergesAllBuiltInDevicesIntoOne() {
        val speaker = device(
            OutputDeviceType.BUILT_IN,
            AudioDeviceIdentity(internalType = 0x2, address = ""),
            "Speaker",
        )
        val earpiece = device(
            OutputDeviceType.BUILT_IN,
            AudioDeviceIdentity(internalType = 0x1, address = ""),
            "Earpiece",
        )

        val consolidated = consolidator.consolidate(listOf(speaker, earpiece))

        assertEquals(1, consolidated.size)
        assertEquals(OutputDeviceType.BUILT_IN, consolidated.single().type)
        assertEquals("Speaker", consolidated.single().productName)
        assertEquals(
            listOf(
                AudioDeviceIdentity(0x2, ""),
                AudioDeviceIdentity(0x1, ""),
            ),
            consolidated.single().candidates,
        )
    }

    @Test
    fun usesFirstNonEmptyBuiltInProductName() {
        val unnamed = device(OutputDeviceType.BUILT_IN, AudioDeviceIdentity(0x2, ""), "")
        val named = device(OutputDeviceType.BUILT_IN, AudioDeviceIdentity(0x1, ""), "Phone")

        val consolidated = consolidator.consolidate(listOf(unnamed, named))

        assertEquals("Phone", consolidated.single().productName)
    }

    @Test
    fun mergesBluetoothProfilesWithSameAddress() {
        val address = "AA:BB:CC:DD:EE:FF"
        val a2dp = device(OutputDeviceType.BLUETOOTH, AudioDeviceIdentity(0x80, address), "WH-1000XM")
        val ble = device(OutputDeviceType.BLUETOOTH, AudioDeviceIdentity(0x2000000, address), "WH-1000XM LE")

        val consolidated = consolidator.consolidate(listOf(a2dp, ble))

        assertEquals(1, consolidated.size)
        assertEquals(OutputDeviceType.BLUETOOTH, consolidated.single().type)
        assertEquals("WH-1000XM", consolidated.single().productName)
        assertEquals(
            listOf(
                AudioDeviceIdentity(0x80, address),
                AudioDeviceIdentity(0x2000000, address),
            ),
            consolidated.single().candidates,
        )
    }

    @Test
    fun mergesNamelessBluetoothProfilesThatShareAnAddress() {
        val a2dp = device(OutputDeviceType.BLUETOOTH, AudioDeviceIdentity(0x80, "AA:BB:CC:DD:EE:FF"), "type:8")
        val ble = device(OutputDeviceType.BLUETOOTH, AudioDeviceIdentity(0x20000000, "aa:bb:cc:dd:ee:ff"), "type:26")

        val consolidated = consolidator.consolidate(listOf(a2dp, ble))

        assertEquals(1, consolidated.size)
        assertEquals("", consolidated.single().productName)
        assertEquals(2, consolidated.single().candidates.size)
    }

    @Test
    fun mergesNamedBluetoothWithNamelessProfileOnSameAddress() {
        val named = device(OutputDeviceType.BLUETOOTH, AudioDeviceIdentity(0x80, "AA:BB:CC:DD:EE:FF"), "MOMENTUM TW 4")
        val nameless = device(OutputDeviceType.BLUETOOTH, AudioDeviceIdentity(0x20000000, "aabbccddeeff"), "type:26")

        val consolidated = consolidator.consolidate(listOf(named, nameless))

        assertEquals(1, consolidated.size)
        assertEquals("MOMENTUM TW 4", consolidated.single().productName)
        assertEquals(2, consolidated.single().candidates.size)
    }

    @Test
    fun mergesBluetoothDevicesWithSameNormalizedNameAndDifferentAddresses() {
        val a2dp = device(OutputDeviceType.BLUETOOTH, AudioDeviceIdentity(0x80, "AA:BB:CC:DD:EE:FF"), "MOMENTUM TW 4")
        val ble = device(OutputDeviceType.BLUETOOTH, AudioDeviceIdentity(0x20000000, "11:22:33:44:55:66"), "MOMENTUM TW 4 LE")

        val consolidated = consolidator.consolidate(listOf(a2dp, ble))

        assertEquals(1, consolidated.size)
        assertEquals(OutputDeviceType.BLUETOOTH, consolidated.single().type)
        assertEquals("MOMENTUM TW 4", consolidated.single().productName)
        assertEquals(2, consolidated.single().candidates.size)
    }

    @Test
    fun mergesBluetoothAddressesIgnoringSeparatorsAndCase() {
        val colon = device(OutputDeviceType.BLUETOOTH, AudioDeviceIdentity(0x80, "aa:bb:cc:dd:ee:ff"), "")
        val plain = device(OutputDeviceType.BLUETOOTH, AudioDeviceIdentity(0x10, "AABBCCDDEEFF"), "")

        val consolidated = consolidator.consolidate(listOf(colon, plain))

        assertEquals(1, consolidated.size)
        assertEquals(2, consolidated.single().candidates.size)
    }

    @Test
    fun keepsBluetoothDevicesWithDifferentAddressesSeparate() {
        val left = device(OutputDeviceType.BLUETOOTH, AudioDeviceIdentity(0x80, "AA:AA:AA:AA:AA:AA"), "Left")
        val right = device(OutputDeviceType.BLUETOOTH, AudioDeviceIdentity(0x80, "BB:BB:BB:BB:BB:BB"), "Right")

        val consolidated = consolidator.consolidate(listOf(left, right))

        assertEquals(listOf(left, right), consolidated)
    }

    @Test
    fun mergesUsbDevicesWithSameAddress() {
        val address = "usb:1-1"
        val accessory = device(OutputDeviceType.USB, AudioDeviceIdentity(0x2000, address), "DAC")
        val headset = device(OutputDeviceType.USB, AudioDeviceIdentity(0x4000000, address), "DAC Headset")

        val consolidated = consolidator.consolidate(listOf(accessory, headset))

        assertEquals(1, consolidated.size)
        assertEquals(OutputDeviceType.USB, consolidated.single().type)
        assertEquals(
            listOf(
                AudioDeviceIdentity(0x2000, address),
                AudioDeviceIdentity(0x4000000, address),
            ),
            consolidated.single().candidates,
        )
    }

    @Test
    fun doesNotMergeUsbDevicesWithEmptyAddresses() {
        val accessory = device(OutputDeviceType.USB, AudioDeviceIdentity(0x2000, ""), "USB A")
        val headset = device(OutputDeviceType.USB, AudioDeviceIdentity(0x4000000, ""), "USB B")

        val consolidated = consolidator.consolidate(listOf(accessory, headset))

        assertEquals(listOf(accessory, headset), consolidated)
    }

    @Test
    fun mergesWiredHeadsetsWithSameAddress() {
        val address = "wired:0"
        val headset = device(OutputDeviceType.WIRED_HEADSET, AudioDeviceIdentity(0x4, address), "Headset")
        val headphones = device(OutputDeviceType.WIRED_HEADSET, AudioDeviceIdentity(0x8, address), "Headphones")

        val consolidated = consolidator.consolidate(listOf(headset, headphones))

        assertEquals(1, consolidated.size)
        assertEquals(OutputDeviceType.WIRED_HEADSET, consolidated.single().type)
        assertEquals(
            listOf(
                AudioDeviceIdentity(0x4, address),
                AudioDeviceIdentity(0x8, address),
            ),
            consolidated.single().candidates,
        )
    }

    @Test
    fun keepsOtherDevicesByIdentity() {
        val first = device(OutputDeviceType.OTHER, AudioDeviceIdentity(0x400, "hdmi:0"), "HDMI")
        val duplicate = device(OutputDeviceType.OTHER, AudioDeviceIdentity(0x400, "hdmi:0"), "HDMI TV")
        val second = device(OutputDeviceType.OTHER, AudioDeviceIdentity(0x400, "hdmi:1"), "HDMI 2")

        val consolidated = consolidator.consolidate(listOf(first, duplicate, second))

        assertEquals(2, consolidated.size)
        assertEquals(listOf(AudioDeviceIdentity(0x400, "hdmi:0")), consolidated[0].candidates)
        assertEquals("HDMI", consolidated[0].productName)
        assertEquals(second, consolidated[1])
    }

    @Test
    fun doesNotTreatSyntheticTypeNamesAsProductNames() {
        val first = device(OutputDeviceType.USB, AudioDeviceIdentity(0x2000, "usb:a"), "type:11")
        val second = device(OutputDeviceType.USB, AudioDeviceIdentity(0x4000000, "usb:b"), "type:22")

        val consolidated = consolidator.consolidate(listOf(first, second))

        assertEquals(2, consolidated.size)
    }

    @Test
    fun doesNotMergeAcrossTypesEvenWithSameAddress() {
        val address = "shared"
        val bluetooth = device(OutputDeviceType.BLUETOOTH, AudioDeviceIdentity(0x80, address), "BT")
        val usb = device(OutputDeviceType.USB, AudioDeviceIdentity(0x2000, address), "USB")

        assertEquals(listOf(bluetooth, usb), consolidator.consolidate(listOf(bluetooth, usb)))
    }

    private fun device(
        type: OutputDeviceType,
        identity: AudioDeviceIdentity,
        productName: String,
    ): AudioOutputDevice = AudioOutputDevice(type, listOf(identity), productName)
}
