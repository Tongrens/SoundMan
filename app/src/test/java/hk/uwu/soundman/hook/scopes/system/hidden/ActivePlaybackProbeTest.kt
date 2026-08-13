package hk.uwu.soundman.hook.scopes.system.hidden

import hk.uwu.soundman.hook.scopes.system.hidden.fakes.FakePlaybackConfiguration
import hk.uwu.soundman.hook.scopes.system.hidden.fakes.FakePlaybackConfigurationWithoutClientUid
import hk.uwu.soundman.hook.scopes.system.hidden.fakes.FakePlaybackConfigurationWithoutOptional
import hk.uwu.soundman.hook.scopes.system.hidden.fakes.FakePlayer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ActivePlaybackProbeTest {
    private val logs = ArrayList<Pair<String, Throwable>>()
    private val probe = ActivePlaybackProbe(PlaybackConfigurationAccess()) { message, throwable ->
        logs += message to throwable
    }

    @Test
    fun keepsActiveUidsAndSkipsInactiveOrInvalid() {
        val player = FakePlayer()
        val probed = probe.probeConfigurations(
            listOf(
                FakePlaybackConfiguration(1001, true, 11, player),
                FakePlaybackConfiguration(1002, false, 12, null),
                FakePlaybackConfiguration(-3, true, 13, null),
                FakePlaybackConfigurationWithoutOptional(2002, true),
                FakePlaybackConfigurationWithoutClientUid(),
            ),
        )

        assertEquals(2, probed.size)
        assertEquals(1001, probed[0].uid)
        assertEquals(11, probed[0].piid)
        val wrapped = probed[0].player ?: error("expected HiddenPlayer")
        wrapped.setVolume(0.25f)
        assertEquals(0.25f, player.lastVolume, 0f)

        assertEquals(2002, probed[1].uid)
        assertNull(probed[1].piid)
        assertNull(probed[1].player)

        assertEquals(1, logs.size)
        assertTrue(logs[0].first.contains("getClientUid"))
        assertTrue(logs[0].first.contains(FakePlaybackConfigurationWithoutClientUid::class.java.name))
    }
}
