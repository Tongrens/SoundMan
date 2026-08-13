package hk.uwu.soundman.ipc

import android.os.RemoteException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HostIpcRecoveryTest {
    @Test
    fun remoteExceptionIsFatal() {
        assertTrue(HostIpcRecovery.isFatalHostFailure(RemoteException("dead")))
        assertTrue(
            HostIpcRecovery.isFatalHostFailure(
                IllegalStateException("wrap", RemoteException("dead")),
            ),
        )
    }

    @Test
    fun closedHostRuntimeExceptionIsFatal() {
        assertTrue(HostIpcRecovery.isFatalHostFailure(IllegalStateException("SoundMan host is closed: setRoute")))
        assertTrue(HostIpcRecovery.isFatalHostFailure(IllegalStateException("SoundMan host is not connected")))
        assertTrue(HostIpcRecovery.isFatalHostFailure(IllegalStateException("Hook generation is closed")))
    }

    @Test
    fun ordinaryHostRejectionIsNotFatal() {
        assertFalse(HostIpcRecovery.isFatalHostFailure(IllegalArgumentException("uid must be non-negative")))
        assertFalse(HostIpcRecovery.isFatalHostFailure(IllegalStateException("rules contain duplicate package names")))
    }
}
