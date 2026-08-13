package hk.uwu.soundman.ipc

import android.os.Binder
import android.os.IBinder
import android.os.Process
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class HostOfferMailboxTest {
    @Test
    fun systemUidDispatchesBinderAndVersion() {
        val hostBinder = Binder()
        val received = RecordingDispatch()
        val mailbox = HostOfferMailbox(
            callingUid = { Process.SYSTEM_UID },
            dispatch = received::invoke,
        )

        mailbox.onHostOffered(hostBinder, SoundManProtocol.VERSION)

        assertEquals(1, received.invocations)
        assertSame(hostBinder, received.hostBinder)
        assertEquals(SoundManProtocol.VERSION, received.protocolVersion)
    }

    @Test
    fun nonSystemUidThrowsSecurityException() {
        val received = RecordingDispatch()
        val mailbox = HostOfferMailbox(
            callingUid = { Process.SYSTEM_UID + 1 },
            dispatch = received::invoke,
        )

        val error = assertThrows(SecurityException::class.java) {
            mailbox.onHostOffered(Binder(), SoundManProtocol.VERSION)
        }
        assertTrue(error.message.orEmpty().contains("SYSTEM_UID"))
        assertEquals(0, received.invocations)
    }

    @Test
    fun nullHostBinderFails() {
        val received = RecordingDispatch()
        val mailbox = HostOfferMailbox(
            callingUid = { Process.SYSTEM_UID },
            dispatch = received::invoke,
        )

        val error = assertThrows(IllegalArgumentException::class.java) {
            mailbox.onHostOffered(null, SoundManProtocol.VERSION)
        }
        assertTrue(error.message.orEmpty().contains("hostBinder"))
        assertEquals(0, received.invocations)
    }

    @Test
    fun versionMismatchFails() {
        val received = RecordingDispatch()
        val mailbox = HostOfferMailbox(
            callingUid = { Process.SYSTEM_UID },
            expectedVersion = SoundManProtocol.VERSION,
            dispatch = received::invoke,
        )

        val error = assertThrows(IllegalArgumentException::class.java) {
            mailbox.onHostOffered(Binder(), SoundManProtocol.VERSION + 1)
        }
        assertTrue(error.message.orEmpty().contains("${SoundManProtocol.VERSION + 1}"))
        assertTrue(error.message.orEmpty().contains("${SoundManProtocol.VERSION}"))
        assertEquals(0, received.invocations)
    }

    @Test
    fun mailboxOnlyInvokesDispatchAndDoesNotTouchHostSession() {
        val events = mutableListOf<String>()
        val mailbox = HostOfferMailbox(
            callingUid = { Process.SYSTEM_UID },
            dispatch = { _, _ -> events += "dispatch" },
        )

        mailbox.onHostOffered(Binder(), SoundManProtocol.VERSION)

        assertEquals(listOf("dispatch"), events)
    }

    private class RecordingDispatch {
        var hostBinder: IBinder? = null
        var protocolVersion: Int = Int.MIN_VALUE
        var invocations: Int = 0

        fun invoke(hostBinder: IBinder, protocolVersion: Int) {
            invocations += 1
            this.hostBinder = hostBinder
            this.protocolVersion = protocolVersion
        }
    }
}
