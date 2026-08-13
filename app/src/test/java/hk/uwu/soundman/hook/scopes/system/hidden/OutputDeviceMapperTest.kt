package hk.uwu.soundman.hook.scopes.system.hidden

import android.media.AudioDeviceInfo
import hk.uwu.soundman.model.OutputDeviceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class OutputDeviceMapperTest {
    private val speakerInternal = 0x2
    private val earpieceInternal = 0x1
    private val a2dpInternal = 0x80
    private val mapping = mapOf(
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER to speakerInternal,
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE to earpieceInternal,
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP to a2dpInternal,
    )
    private val mapper = OutputDeviceMapper(mapping::get)

    @Test
    fun mapsBuiltInSpeakerWithEmptyAddress() {
        val device = mapper.map(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, "", "Speaker")
        assertNotNull(device)
        assertEquals(OutputDeviceType.BUILT_IN, device!!.type)
        assertEquals("", device.identity.address)
        assertEquals(speakerInternal, device.identity.internalType)
        assertEquals("Speaker", device.productName)
    }

    @Test
    fun mapsBuiltInEarpieceWithEmptyAddress() {
        val device = mapper.map(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE, "", "Earpiece")
        assertNotNull(device)
        assertEquals(OutputDeviceType.BUILT_IN, device!!.type)
        assertEquals("", device.identity.address)
        assertEquals(earpieceInternal, device.identity.internalType)
    }

    @Test
    fun dropsA2dpWhenAddressIsEmpty() {
        assertNull(mapper.map(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, "", "WH-1000XM"))
    }

    @Test
    fun mapsA2dpWhenAddressIsPresent() {
        val device = mapper.map(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, "AA:BB:CC:DD:EE:FF", "WH-1000XM")
        assertNotNull(device)
        assertEquals(OutputDeviceType.BLUETOOTH, device!!.type)
        assertEquals("AA:BB:CC:DD:EE:FF", device.identity.address)
        assertEquals(a2dpInternal, device.identity.internalType)
        assertEquals("WH-1000XM", device.productName)
    }

    @Test
    fun returnsNullForUnknownPublicType() {
        assertNull(mapper.map(AudioDeviceInfo.TYPE_UNKNOWN, "addr", "unknown"))
        assertNull(mapper.map(999, "", "mystery"))
    }
}
