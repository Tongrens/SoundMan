package hk.uwu.soundman.hook.scopes.system.runtime

import hk.uwu.soundman.model.AudioDeviceIdentity
import hk.uwu.soundman.model.OutputDeviceType
import hk.uwu.soundman.model.OutputTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteDebugTest {
    @Test
    fun describeTargetDistinguishesFollowSystemAndDevice() {
        assertEquals("FollowSystem", RouteDebug.describeTarget(OutputTarget.FollowSystem))
        val device = OutputTarget.Device(
            type = OutputDeviceType.BLUETOOTH,
            candidates = listOf(AudioDeviceIdentity(0x80, "AA:BB:CC:DD:EE:FF")),
            productName = "WH-1000XM",
        )
        val text = RouteDebug.describeTarget(device)
        assertTrue(text.contains("BLUETOOTH"))
        assertTrue(text.contains("WH-1000XM"))
        assertTrue(text.contains("0x80") || text.contains("128"))
        assertTrue(text.contains("AA:BB:CC:DD:EE:FF"))
    }

    @Test
    fun describeConnectionMarksAvailableAgainstState() {
        val candidate = AudioDeviceIdentity(0x2, "")
        assertEquals(
            "type=2 address=<empty> state=1 available=1 connected=true",
            RouteDebug.describeConnection(candidate, state = 1, available = 1),
        )
        assertEquals(
            "type=2 address=<empty> state=0 available=1 connected=false",
            RouteDebug.describeConnection(candidate, state = 0, available = 1),
        )
    }

    @Test
    fun describePlayersFlagsLivePlayback() {
        assertEquals("started=2 tracked=3 live=true", RouteDebug.describePlayers(2, 3))
        assertEquals("started=0 tracked=1 live=false", RouteDebug.describePlayers(0, 1))
    }
}
