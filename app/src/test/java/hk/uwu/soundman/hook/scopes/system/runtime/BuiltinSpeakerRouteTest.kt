package hk.uwu.soundman.hook.scopes.system.runtime

import hk.uwu.soundman.model.AudioDeviceIdentity
import hk.uwu.soundman.model.OutputDeviceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BuiltinSpeakerRouteTest {
    @Test
    fun builtinKeepsOnlySpeaker() {
        val earpiece = AudioDeviceIdentity(1, "")
        val speaker = AudioDeviceIdentity(2, "")
        val picked = BuiltinSpeakerRoute.pick(
            type = OutputDeviceType.BUILT_IN,
            candidates = listOf(earpiece, speaker),
            speakerInternalType = 2,
        )
        assertEquals(listOf(speaker), picked)
    }

    @Test
    fun builtinWithoutSpeakerIsEmpty() {
        val picked = BuiltinSpeakerRoute.pick(
            type = OutputDeviceType.BUILT_IN,
            candidates = listOf(AudioDeviceIdentity(1, "")),
            speakerInternalType = 2,
        )
        assertTrue(picked.isEmpty())
    }

    @Test
    fun bluetoothIsUnchanged() {
        val a2dp = AudioDeviceIdentity(0x80, "AA:BB:CC:DD:EE:FF")
        val ble = AudioDeviceIdentity(0x20000000, "AA:BB:CC:DD:EE:FF")
        val picked = BuiltinSpeakerRoute.pick(
            type = OutputDeviceType.BLUETOOTH,
            candidates = listOf(a2dp, ble),
            speakerInternalType = 2,
        )
        assertEquals(listOf(a2dp, ble), picked)
    }
}
