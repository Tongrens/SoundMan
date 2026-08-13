package hk.uwu.soundman.ipc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PreferredDeviceUsageTest {
    @Test
    fun followSystemStaysMedia() {
        val usages = PreferredDeviceUsage.allocate(
            listOf(PreferredDeviceSync.followSystem(10)),
        )
        assertEquals(PreferredDeviceUsage.USAGE_MEDIA, usages[10])
        assertFalse(PreferredDeviceUsage.shouldRewrite(usages.getValue(10)))
    }

    @Test
    fun sameDeviceSharesUsage() {
        val speakerA = PreferredDeviceSync.forced(1, 2, "")
        val speakerB = PreferredDeviceSync.forced(2, 2, "")
        val usages = PreferredDeviceUsage.allocate(listOf(speakerA, speakerB))
        assertEquals(usages[1], usages[2])
        assertEquals(PreferredDeviceUsage.USAGE_GAME, usages[1])
        assertTrue(PreferredDeviceUsage.shouldRewrite(usages.getValue(1)))
    }

    @Test
    fun differentDevicesGetDifferentUsages() {
        val speaker = PreferredDeviceSync.forced(1, 2, "")
        val bt = PreferredDeviceSync.forced(2, 8, "AA:BB")
        val usages = PreferredDeviceUsage.allocate(listOf(speaker, bt))
        assertEquals(PreferredDeviceUsage.USAGE_GAME, usages[1])
        assertEquals(PreferredDeviceUsage.USAGE_ASSISTANT, usages[2])
    }

    @Test
    fun fourDevicesFillThePool() {
        val hints = listOf(
            PreferredDeviceSync.forced(1, 2, ""),
            PreferredDeviceSync.forced(2, 8, "A"),
            PreferredDeviceSync.forced(3, 8, "B"),
            PreferredDeviceSync.forced(4, 3, ""),
        )
        val usages = PreferredDeviceUsage.allocate(hints)
        assertEquals(
            setOf(
                PreferredDeviceUsage.USAGE_GAME,
                PreferredDeviceUsage.USAGE_ASSISTANT,
                PreferredDeviceUsage.USAGE_NAVIGATION,
                PreferredDeviceUsage.USAGE_ACCESSIBILITY,
            ),
            usages.values.toSet(),
        )
    }

    @Test
    fun mixedFollowSystemDoesNotConsumePool() {
        val follow = PreferredDeviceSync.followSystem(1)
        val speaker = PreferredDeviceSync.forced(2, 2, "")
        val usages = PreferredDeviceUsage.allocate(listOf(follow, speaker))
        assertEquals(PreferredDeviceUsage.USAGE_MEDIA, usages[1])
        assertEquals(PreferredDeviceUsage.USAGE_GAME, usages[2])
    }

    @Test
    fun withAllocatedUsagesWritesBack() {
        val allocated = PreferredDeviceUsage.withAllocatedUsages(
            listOf(
                PreferredDeviceSync.forced(8, 2, ""),
                PreferredDeviceSync.forced(3, 8, "AA"),
            ),
        )
        assertEquals(PreferredDeviceUsage.USAGE_ASSISTANT, allocated.single { it.uid == 8 }.usage)
        assertEquals(PreferredDeviceUsage.USAGE_GAME, allocated.single { it.uid == 3 }.usage)
    }
}
