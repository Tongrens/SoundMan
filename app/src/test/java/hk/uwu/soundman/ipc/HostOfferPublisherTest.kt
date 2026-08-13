package hk.uwu.soundman.ipc

import android.os.Binder
import android.os.IBinder
import hk.uwu.soundman.internal.ipc.ISoundManHostOffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class HostOfferPublisherTest {
    @Test
    fun offerDeliversHostBinderAndVersionToMailbox() {
        val mailbox = RecordingHostOffer()
        val offerBinder = Binder()
        val hostBinder = Binder()
        val publisher = HostOfferPublisher { binder ->
            assertSame(offerBinder, binder)
            mailbox
        }

        publisher.offer(
            extras = SoundManProtocol.requestBinderExtras(offerBinder),
            hostBinder = hostBinder,
        )

        assertEquals(1, mailbox.invocations)
        assertSame(hostBinder, mailbox.hostBinder)
        assertEquals(SoundManProtocol.VERSION, mailbox.protocolVersion)
    }

    @Test
    fun missingOfferBinderFailsWithFieldName() {
        val publisher = HostOfferPublisher { error("resolveOffer must not run") }
        val error = assertThrows(IllegalArgumentException::class.java) {
            publisher.offer(
                extras = mapOf(SoundManProtocol.EXTRA_PROTOCOL_VERSION to SoundManProtocol.VERSION),
                hostBinder = Binder(),
            )
        }
        assertTrue(error.message.orEmpty().contains(SoundManProtocol.EXTRA_HOST_OFFER))
    }

    @Test
    fun versionMismatchFailsWithVersions() {
        val publisher = HostOfferPublisher { error("resolveOffer must not run") }
        val error = assertThrows(IllegalArgumentException::class.java) {
            publisher.offer(
                extras = mapOf(
                    SoundManProtocol.EXTRA_PROTOCOL_VERSION to SoundManProtocol.VERSION - 1,
                    SoundManProtocol.EXTRA_HOST_OFFER to Binder(),
                ),
                hostBinder = Binder(),
            )
        }
        assertTrue(error.message.orEmpty().contains("${SoundManProtocol.VERSION - 1}"))
        assertTrue(error.message.orEmpty().contains("${SoundManProtocol.VERSION}"))
    }

    private class RecordingHostOffer : ISoundManHostOffer {
        var hostBinder: IBinder? = null
        var protocolVersion: Int = Int.MIN_VALUE
        var invocations: Int = 0

        override fun asBinder(): IBinder = throw UnsupportedOperationException("local fake has no binder")

        override fun onHostOffered(hostBinder: IBinder?, protocolVersion: Int) {
            invocations += 1
            this.hostBinder = hostBinder
            this.protocolVersion = protocolVersion
        }
    }
}
