package hk.uwu.soundman.hook.scopes.system.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SnapshotPlaybackMergeTest {
    private val merge = SnapshotPlaybackMerge()

    @Test
    fun unionsStartedAndProbedUids() {
        val merged = merge.merge(
            startedCounts = mapOf(1001 to 2, 1003 to 1),
            probedUids = setOf(1001, 2002),
        )
        assertEquals(listOf(1001, 1003, 2002), merged.keys.toList())
        assertEquals(2, merged[1001])
        assertEquals(1, merged[1003])
        assertEquals(1, merged[2002])
    }

    @Test
    fun usesProbedUidWhenHookRecordsAreEmpty() {
        val merged = merge.merge(startedCounts = emptyMap(), probedUids = setOf(7, 3))
        assertEquals(listOf(3, 7), merged.keys.toList())
        assertEquals(1, merged[3])
        assertEquals(1, merged[7])
    }

    @Test
    fun returnsEmptyWhenBothSidesAreEmpty() {
        assertTrue(merge.merge(emptyMap(), emptySet()).isEmpty())
    }
}
