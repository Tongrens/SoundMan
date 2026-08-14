package hk.uwu.soundman.hook.scopes.system.hidden

import hk.uwu.soundman.hook.scopes.system.hidden.fakes.FakeMediaPlaybackConfiguration
import hk.uwu.soundman.hook.scopes.system.hidden.fakes.FakePlaybackConfiguration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackProbeFactoryTest {
    private val access = PlaybackConfigurationAccess()
    private val mediaAccess = MediaPlaybackAccess()

    @Test
    fun currentKindIsMediaFiltered() {
        assertEquals(PlaybackProbeFactory.Kind.MEDIA_FILTERED, PlaybackProbeFactory.current)
    }

    @Test
    fun selectedCreateUsesMediaFilteredProbe() {
        val probe = PlaybackProbeFactory.create(
            access = access,
            mediaAccess = mediaAccess,
            packageNameForUid = { "com.example" },
            logError = { _, _ -> },
        )
        assertTrue(probe is MediaPlaybackProbe)
        val probed = (probe as MediaPlaybackProbe).probeConfigurations(
            listOf(FakeMediaPlaybackConfiguration(10100, 2, 1, 3, 1, null)),
        )
        assertEquals(listOf(10100), probed.map(ProbedPlayback::uid))
    }

    @Test
    fun legacyCreateKeepsIsActivePath() {
        val probe = PlaybackProbeFactory.create(
            kind = PlaybackProbeFactory.Kind.LEGACY_ACTIVE,
            access = access,
            mediaAccess = mediaAccess,
            packageNameForUid = { "com.miui.miwallpaper" },
            logError = { _, _ -> },
        )
        assertTrue(probe is ActivePlaybackProbe)
        val probed = (probe as ActivePlaybackProbe).probeConfigurations(
            listOf(
                FakePlaybackConfiguration(9000, true, 1, null),
                FakePlaybackConfiguration(10100, false, 2, null),
            ),
        )
        assertEquals(listOf(9000), probed.map(ProbedPlayback::uid))
    }
}
