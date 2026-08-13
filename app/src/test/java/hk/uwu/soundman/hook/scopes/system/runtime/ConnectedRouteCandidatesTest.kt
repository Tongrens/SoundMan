package hk.uwu.soundman.hook.scopes.system.runtime

import hk.uwu.soundman.model.AudioDeviceIdentity
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConnectedRouteCandidatesTest {
    private val collector = ConnectedRouteCandidates()

    @Test
    fun collectsOnlyConnectedAndAlignsIdsWithAddresses() {
        val a2dp = AudioDeviceIdentity(0x80, "AA:BB:CC:DD:EE:FF")
        val ble = AudioDeviceIdentity(0x2000000, "AA:BB:CC:DD:EE:FF")
        val sco = AudioDeviceIdentity(0x10, "AA:BB:CC:DD:EE:FF")
        val connected = setOf(a2dp, ble)

        val result = collector.collect(listOf(a2dp, ble, sco)) { candidate -> candidate in connected }

        requireNotNull(result)
        assertEquals(listOf(a2dp, ble), result.identities)
        assertArrayEquals(intArrayOf(0x80, 0x2000000), result.deviceIds)
        assertArrayEquals(arrayOf("AA:BB:CC:DD:EE:FF", "AA:BB:CC:DD:EE:FF"), result.deviceAddresses)
        assertEquals(result.identities.size, result.deviceIds.size)
        assertEquals(result.identities.size, result.deviceAddresses.size)
    }

    @Test
    fun returnsNullWhenNoneConnected() {
        val candidate = AudioDeviceIdentity(0x80, "AA:BB:CC:DD:EE:FF")
        assertNull(collector.collect(listOf(candidate)) { false })
    }

    @Test
    fun keepsEmptyAddressForBuiltin() {
        val speaker = AudioDeviceIdentity(0x2, "")
        val earpiece = AudioDeviceIdentity(0x1, "")
        val result = collector.collect(listOf(speaker, earpiece)) { true }

        requireNotNull(result)
        assertEquals(listOf(speaker, earpiece), result.identities)
        assertArrayEquals(intArrayOf(0x2, 0x1), result.deviceIds)
        assertArrayEquals(arrayOf("", ""), result.deviceAddresses)
    }
}
