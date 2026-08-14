package hk.uwu.soundman.hook.scopes.system.hidden

import hk.uwu.soundman.hook.scopes.system.hidden.fakes.FakeMediaPlaybackConfiguration
import hk.uwu.soundman.hook.scopes.system.hidden.fakes.FakeMediaPlaybackConfigurationNullAttributes
import hk.uwu.soundman.hook.scopes.system.hidden.fakes.FakeMediaPlaybackConfigurationWithoutAudioAttributes
import hk.uwu.soundman.hook.scopes.system.hidden.fakes.FakeMediaPlaybackConfigurationWithoutOptional
import hk.uwu.soundman.hook.scopes.system.hidden.fakes.FakeMediaPlaybackConfigurationWithoutPlayerState
import hk.uwu.soundman.hook.scopes.system.hidden.fakes.FakePlayer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaPlaybackProbeTest {
    private val logs = ArrayList<Pair<String, Throwable>>()
    private val namesByUid = HashMap<Int, String?>()
    private val probe = MediaPlaybackProbe(
        PlaybackConfigurationAccess(),
        MediaPlaybackAccess(),
        { uid -> namesByUid[uid] },
    ) { message, throwable ->
        logs += message to throwable
    }

    @Test
    fun keepsStartedMediaAppAndSkipsOthers() {
        val player = FakePlayer()
        namesByUid[10101] = "com.spotify.music"
        namesByUid[10102] = "com.android.systemui"
        namesByUid[10103] = "com.miui.miwallpaper"
        namesByUid[9000] = "android"
        namesByUid[10104] = "com.example.game"
        namesByUid[10105] = "com.example.paused"

        val probed = probe.probeConfigurations(
            listOf(
                media(10101, PLAYER_STARTED, USAGE_MEDIA, STREAM_MUSIC, 11, player),
                media(10102, PLAYER_STARTED, USAGE_MEDIA, STREAM_MUSIC, 12, null),
                media(10103, PLAYER_STARTED, USAGE_MEDIA, STREAM_MUSIC, 13, null),
                media(9000, PLAYER_STARTED, USAGE_MEDIA, STREAM_MUSIC, 14, null),
                media(10104, PLAYER_STARTED, USAGE_GAME, STREAM_ALARM, 15, null),
                media(10105, PLAYER_PAUSED, USAGE_MEDIA, STREAM_MUSIC, 16, null),
                FakeMediaPlaybackConfigurationWithoutOptional(
                    10106,
                    PLAYER_STARTED,
                    USAGE_MEDIA,
                    STREAM_ALARM
                ),
                FakeMediaPlaybackConfigurationWithoutPlayerState(),
                FakeMediaPlaybackConfigurationWithoutAudioAttributes(),
                FakeMediaPlaybackConfigurationNullAttributes(),
                null,
            ),
        )

        assertEquals(listOf(10101, 10102, 10106), probed.map(ProbedPlayback::uid))
        assertEquals(11, probed[0].piid)
        val wrapped = probed[0].player ?: error("expected HiddenPlayer")
        wrapped.setVolume(0.25f)
        assertEquals(0.25f, player.lastVolume, 0f)
        assertEquals(12, probed[1].piid)
        assertNull(probed[1].player)
        assertNull(probed[2].piid)
        assertNull(probed[2].player)

        assertTrue(logs.any { it.first.contains("getPlayerState") })
        assertTrue(logs.any { it.first.contains("getUsage") || it.first.contains("getAudioAttributes") })
        assertTrue(logs.any { it.first.contains("null playback configuration") })
    }

    @Test
    fun keepsNonMediaUsageWhenVolumeControlStreamIsMusic() {
        namesByUid[10110] = "com.example.nav"
        val probed = probe.probeConfigurations(
            listOf(
                media(
                    10110,
                    PLAYER_STARTED,
                    USAGE_ASSISTANCE_NAVIGATION,
                    STREAM_MUSIC,
                    21,
                    null
                )
            ),
        )
        assertEquals(listOf(10110), probed.map(ProbedPlayback::uid))
    }

    @Test
    fun keepsEmptyPackageNameAndDedupesUid() {
        namesByUid[10120] = ""
        namesByUid[10121] = null
        val probed = probe.probeConfigurations(
            listOf(
                media(10120, PLAYER_STARTED, USAGE_MEDIA, STREAM_ALARM, 31, null),
                media(10121, PLAYER_STARTED, USAGE_MEDIA, STREAM_MUSIC, 32, null),
                media(10121, PLAYER_STARTED, USAGE_MEDIA, STREAM_MUSIC, 33, null),
            ),
        )
        assertEquals(listOf(10120, 10121), probed.map(ProbedPlayback::uid))
        assertEquals(33, probed[1].piid)
    }

    private fun media(
        uid: Int,
        playerState: Int,
        usage: Int,
        stream: Int,
        piid: Int,
        player: Any?,
    ) = FakeMediaPlaybackConfiguration(uid, playerState, usage, stream, piid, player)

    private companion object {
        const val PLAYER_STARTED = 2
        const val PLAYER_PAUSED = 3
        const val USAGE_MEDIA = 1
        const val USAGE_GAME = 14
        const val USAGE_ASSISTANCE_NAVIGATION = 12
        const val STREAM_MUSIC = 3
        const val STREAM_ALARM = 4
    }
}
