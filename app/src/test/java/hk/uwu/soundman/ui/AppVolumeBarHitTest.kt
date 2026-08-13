package hk.uwu.soundman.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AppVolumeBarHitTest {
    @Test
    fun moreHitUsesRightFraction() {
        val width = 200f
        val boundary = width * (1f - AppVolumeBarHit.MORE_HIT_FRACTION)

        assertFalse(AppVolumeBarHit.isMoreHit(0f, width))
        assertFalse(AppVolumeBarHit.isMoreHit(boundary - 0.01f, width))
        assertTrue(AppVolumeBarHit.isMoreHit(boundary, width))
        assertTrue(AppVolumeBarHit.isMoreHit(width, width))
    }

    @Test
    fun volumeFromOffsetMapsLeftToZeroAndRightToFull() {
        val width = 200f

        assertEquals(0, AppVolumeBarHit.volumeFromOffset(0f, width))
        assertEquals(50, AppVolumeBarHit.volumeFromOffset(width / 2f, width))
        assertEquals(100, AppVolumeBarHit.volumeFromOffset(width, width))
    }

    @Test
    fun moreZoneStillMapsHighVolume() {
        val width = 200f
        val moreStart = width * (1f - AppVolumeBarHit.MORE_HIT_FRACTION)

        assertTrue(AppVolumeBarHit.volumeFromOffset(moreStart, width) >= 80)
        assertEquals(100, AppVolumeBarHit.volumeFromOffset(width, width))
    }

    @Test
    fun moreIconCoverageFollowsFillAcrossMoreZone() {
        val more = AppVolumeBarHit.MORE_HIT_FRACTION
        val start = 1f - more

        assertEquals(0f, AppVolumeBarHit.moreIconFillCoverage(0f), 0.0001f)
        assertEquals(0f, AppVolumeBarHit.moreIconFillCoverage(start), 0.0001f)
        assertEquals(0.5f, AppVolumeBarHit.moreIconFillCoverage(start + more / 2f), 0.0001f)
        assertEquals(1f, AppVolumeBarHit.moreIconFillCoverage(1f), 0.0001f)
    }

    @Test
    fun movementBeyondSlopIsDragEvenInsideMoreZone() {
        assertFalse(AppVolumeBarHit.isDragPastSlop(distance = 4f, slop = 8f))
        assertTrue(AppVolumeBarHit.isDragPastSlop(distance = 8f, slop = 8f))
        assertTrue(AppVolumeBarHit.isDragPastSlop(distance = 20f, slop = 8f))
    }

    @Test
    fun rejectsNonPositiveWidth() {
        assertThrows(IllegalArgumentException::class.java) {
            AppVolumeBarHit.isMoreHit(0f, 0f)
        }
        assertThrows(IllegalArgumentException::class.java) {
            AppVolumeBarHit.volumeFromOffset(0f, 0f)
        }
        assertThrows(IllegalArgumentException::class.java) {
            AppVolumeBarHit.isMoreHit(0f, -1f)
        }
        assertThrows(IllegalArgumentException::class.java) {
            AppVolumeBarHit.volumeFromOffset(10f, -8f)
        }
        assertThrows(IllegalArgumentException::class.java) {
            AppVolumeBarHit.moreIconFillCoverage(-0.1f)
        }
        assertThrows(IllegalArgumentException::class.java) {
            AppVolumeBarHit.isDragPastSlop(1f, 0f)
        }
    }
}
