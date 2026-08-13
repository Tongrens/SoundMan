package hk.uwu.soundman.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackPresenceDebouncerTest {
    @Test
    fun songSwitchGapDoesNotClearVisibleApps() {
        val debouncer = newDebouncer()

        assertEquals(listOf("spotify"), debouncer.update(listOf("spotify"), 0L))
        assertEquals(listOf("spotify"), debouncer.update(emptyList(), 20L))
        assertEquals(listOf("spotify"), debouncer.update(listOf("spotify"), 200L))
        assertEquals(listOf("spotify"), debouncer.update(listOf("spotify"), 800L))
        assertNull(debouncer.nextDeadlineMillis())
    }

    @Test
    fun realStopRemovesOnlyAfterHoldWindow() {
        val debouncer = newDebouncer()

        assertEquals(listOf("spotify"), debouncer.update(listOf("spotify"), 0L))
        assertEquals(listOf("spotify"), debouncer.update(emptyList(), 100L))
        assertEquals(550L, debouncer.nextDeadlineMillis())
        assertEquals(listOf("spotify"), debouncer.update(emptyList(), 549L))
        assertEquals(emptyList<String>(), debouncer.update(emptyList(), 550L))
        assertNull(debouncer.nextDeadlineMillis())
    }

    @Test
    fun newAppAppearsImmediately() {
        val debouncer = newDebouncer()

        assertEquals(emptyList<String>(), debouncer.update(emptyList(), 0L))
        assertEquals(listOf("new.player"), debouncer.update(listOf("new.player"), 1L))
        assertNull(debouncer.nextDeadlineMillis())
    }

    @Test
    fun newAppAppearsWhileAnotherIsStillHeld() {
        val debouncer = newDebouncer()

        debouncer.update(listOf("old.player"), 0L)
        val visible = debouncer.update(listOf("new.player"), 10L)

        assertEquals(listOf("new.player", "old.player"), visible)
        assertEquals(460L, debouncer.nextDeadlineMillis())
    }

    @Test
    fun returningAppCancelsPendingRemoval() {
        val debouncer = newDebouncer()

        debouncer.update(listOf("spotify"), 0L)
        debouncer.update(emptyList(), 100L)
        assertTrue(debouncer.nextDeadlineMillis() != null)
        assertEquals(listOf("spotify"), debouncer.update(listOf("spotify"), 400L))
        assertNull(debouncer.nextDeadlineMillis())
        assertEquals(listOf("spotify"), debouncer.update(emptyList(), 549L))
    }

    private fun newDebouncer(): PlaybackPresenceDebouncer<String> =
        PlaybackPresenceDebouncer(holdMillis = 450L, keyOf = { it })
}
