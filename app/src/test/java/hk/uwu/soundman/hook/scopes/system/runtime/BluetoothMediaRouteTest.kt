package hk.uwu.soundman.hook.scopes.system.runtime

import hk.uwu.soundman.model.AudioDeviceIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BluetoothMediaRouteTest {
    @Test
    fun dropsDummyScoAndKeepsA2dp() {
        val sco = AudioDeviceIdentity(0x10, "00:00:00:00:00:00")
        val a2dp = AudioDeviceIdentity(0x80, "80:C3:BA:78:4A:7D")
        val picked = BluetoothMediaRoute.pick(listOf(sco, a2dp), scoInternalType = 0x10)
        assertEquals(listOf(a2dp), picked)
    }

    @Test
    fun dummyAddressDetection() {
        assertTrue(BluetoothMediaRoute.isDummyAddress(""))
        assertTrue(BluetoothMediaRoute.isDummyAddress("00:00:00:00:00:00"))
        assertFalse(BluetoothMediaRoute.isDummyAddress("80:C3:BA:78:4A:7D"))
    }
}
