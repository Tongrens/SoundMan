package hk.uwu.soundman.hook.scopes.system.hidden

import hk.uwu.soundman.hook.scopes.system.hidden.fakes.FakeAudioAttributes
import hk.uwu.soundman.hook.scopes.system.hidden.fakes.FakeMediaPlaybackConfiguration
import hk.uwu.soundman.hook.scopes.system.hidden.fakes.FakeMediaPlaybackConfigurationNullAttributes
import hk.uwu.soundman.hook.scopes.system.hidden.fakes.FakeMediaPlaybackConfigurationWithoutAudioAttributes
import hk.uwu.soundman.hook.scopes.system.hidden.fakes.FakeMediaPlaybackConfigurationWithoutPlayerState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaPlaybackAccessTest {
    private val access = MediaPlaybackAccess()

    @Test
    fun readsPlayerStateUsageAndStream() {
        val config = FakeMediaPlaybackConfiguration(10123, 2, 1, 3, 11, null)
        assertEquals(2, access.playerState(config))
        assertEquals(1, access.usage(config))
        assertEquals(3, access.volumeControlStream(config))
    }

    @Test
    fun failsWhenPlayerStateIsMissing() {
        val error = assertThrows(IllegalStateException::class.java) {
            access.playerState(FakeMediaPlaybackConfigurationWithoutPlayerState())
        }
        assertTrue(error.message.orEmpty().contains("getPlayerState"))
        assertTrue(
            error.message.orEmpty()
                .contains(FakeMediaPlaybackConfigurationWithoutPlayerState::class.java.name)
        )
    }

    @Test
    fun failsWhenAudioAttributesAreMissing() {
        val error = assertThrows(IllegalStateException::class.java) {
            access.usage(FakeMediaPlaybackConfigurationWithoutAudioAttributes())
        }
        assertTrue(error.message.orEmpty().contains("getAudioAttributes"))
        assertTrue(
            error.message.orEmpty()
                .contains(FakeMediaPlaybackConfigurationWithoutAudioAttributes::class.java.name)
        )
    }

    @Test
    fun failsWhenAudioAttributesAreNull() {
        val error = assertThrows(IllegalStateException::class.java) {
            access.usage(FakeMediaPlaybackConfigurationNullAttributes())
        }
        assertTrue(error.message.orEmpty().contains("getAudioAttributes returned null"))
    }

    @Test
    fun readsNonMediaUsageAndNonMusicStream() {
        val attributes = FakeAudioAttributes(14, 4)
        val config = object {
            fun getPlayerState(): Int = 2
            fun getAudioAttributes(): FakeAudioAttributes = attributes
        }
        assertEquals(2, access.playerState(config))
        assertEquals(14, access.usage(config))
        assertEquals(4, access.volumeControlStream(config))
    }
}
