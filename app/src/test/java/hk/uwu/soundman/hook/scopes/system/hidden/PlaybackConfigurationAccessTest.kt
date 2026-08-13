package hk.uwu.soundman.hook.scopes.system.hidden

import hk.uwu.soundman.hook.scopes.system.hidden.fakes.FakePlaybackConfiguration
import hk.uwu.soundman.hook.scopes.system.hidden.fakes.FakePlaybackConfigurationWithoutClientUid
import hk.uwu.soundman.hook.scopes.system.hidden.fakes.FakePlaybackConfigurationWithoutIsActive
import hk.uwu.soundman.hook.scopes.system.hidden.fakes.FakePlaybackConfigurationWithoutOptional
import hk.uwu.soundman.hook.scopes.system.hidden.fakes.FakePlayer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackConfigurationAccessTest {
    private val access = PlaybackConfigurationAccess()

    @Test
    fun readsUidActivePiidAndPlayer() {
        val fakePlayer = FakePlayer()
        val config = FakePlaybackConfiguration(1001, true, 42, fakePlayer)

        assertEquals(1001, access.clientUid(config))
        assertTrue(access.isActive(config))
        assertEquals(42, access.playerInterfaceId(config))
        val player = access.player(config) ?: error("expected HiddenPlayer")
        player.setVolume(0.5f)
        assertEquals(0.5f, fakePlayer.lastVolume, 0f)
    }

    @Test
    fun returnsNullWhenOptionalMethodsAreMissing() {
        val config = FakePlaybackConfigurationWithoutOptional(10, true)
        assertEquals(10, access.clientUid(config))
        assertTrue(access.isActive(config))
        assertNull(access.playerInterfaceId(config))
        assertNull(access.player(config))
    }

    @Test
    fun returnsNullPlayerWhenProxyIsNull() {
        val config = FakePlaybackConfiguration(7, true, 3, null)
        assertNull(access.player(config))
        assertEquals(3, access.playerInterfaceId(config))
    }

    @Test
    fun failsWhenClientUidIsMissing() {
        val error = assertThrows(IllegalStateException::class.java) {
            access.clientUid(FakePlaybackConfigurationWithoutClientUid())
        }
        assertTrue(error.message.orEmpty().contains("getClientUid"))
        assertTrue(error.message.orEmpty().contains(FakePlaybackConfigurationWithoutClientUid::class.java.name))
    }

    @Test
    fun failsWhenIsActiveIsMissing() {
        val error = assertThrows(IllegalStateException::class.java) {
            access.isActive(FakePlaybackConfigurationWithoutIsActive())
        }
        assertTrue(error.message.orEmpty().contains("isActive"))
        assertTrue(error.message.orEmpty().contains(FakePlaybackConfigurationWithoutIsActive::class.java.name))
    }
}
