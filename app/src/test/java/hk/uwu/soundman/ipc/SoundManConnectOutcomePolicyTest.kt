package hk.uwu.soundman.ipc

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SoundManConnectOutcomePolicyTest {
    @Test
    fun reportsRealConnectionFailureWhileClientIsOpen() {
        assertTrue(
            SoundManConnectOutcomePolicy.shouldReportFailure(
                SoundManConnectOutcome(connected = false, closed = false),
            ),
        )
    }

    @Test
    fun closeReleasedWaitIsNotReportedAsConnectionFailure() {
        assertFalse(
            SoundManConnectOutcomePolicy.shouldReportFailure(
                SoundManConnectOutcome(connected = false, closed = true),
            ),
        )
    }

    @Test
    fun connectedOutcomeIsNeverReportedAsFailure() {
        assertFalse(
            SoundManConnectOutcomePolicy.shouldReportFailure(
                SoundManConnectOutcome(connected = true, closed = false),
            ),
        )
    }
}
