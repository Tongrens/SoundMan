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
    fun relativeDragKeepsVolumeUntilFingerMoves() {
        assertEquals(20f, AppVolumeBarHit.volumeFromRelativeDrag(20, 0f, 200f), 0.0001f)
        assertEquals(80f, AppVolumeBarHit.volumeFromRelativeDrag(80, 0f, 200f), 0.0001f)
    }

    @Test
    fun relativeDragMapsFullWidthToFullRangeFromAnyStart() {
        val width = 200f

        assertEquals(90f, AppVolumeBarHit.volumeFromRelativeDrag(40, width / 2f, width), 0.0001f)
    }

    @Test
    fun relativeDragTracksSubPercentMovement() {
        val width = 200f

        assertEquals(40.5f, AppVolumeBarHit.volumeFromRelativeDrag(40, 1f, width), 0.0001f)
        assertEquals(39.5f, AppVolumeBarHit.volumeFromRelativeDrag(40, -1f, width), 0.0001f)
    }

    @Test
    fun rubberBandLeavesInRangeValuesUnchanged() {
        assertEquals(0f, AppVolumeBarHit.rubberBand(0f), 0.0001f)
        assertEquals(50f, AppVolumeBarHit.rubberBand(50f), 0.0001f)
        assertEquals(100f, AppVolumeBarHit.rubberBand(100f), 0.0001f)
    }

    @Test
    fun rubberBandDampsOverflowPastEnds() {
        val overMax = AppVolumeBarHit.rubberBand(110f)
        val underMin = AppVolumeBarHit.rubberBand(-10f)

        assertTrue(overMax > 100f)
        assertTrue(overMax < 110f)
        assertTrue(underMin < 0f)
        assertTrue(underMin > -10f)
        assertEquals(
            AppVolumeBarHit.rubberBandOverflow(10f, 100f, AppVolumeBarHit.RUBBER_BAND_COEFFICIENT),
            overMax - 100f,
            0.0001f,
        )
    }

    @Test
    fun relativeDragUsesRubberBandPastZeroAndFull() {
        val width = 200f
        val overshot = AppVolumeBarHit.volumeFromRelativeDrag(10, width, width)
        val undershot = AppVolumeBarHit.volumeFromRelativeDrag(40, -width / 2f, width)

        assertTrue(overshot > 100f)
        assertTrue(overshot < 110f)
        assertTrue(undershot < 0f)
        assertTrue(undershot > -10f)
        assertEquals(100, AppVolumeBarHit.committedPercent(overshot))
        assertEquals(0, AppVolumeBarHit.committedPercent(undershot))
    }

    @Test
    fun overflowFeedbackStaysInsideShortSignedDistance() {
        assertEquals(0f, AppVolumeBarHit.internalOverflowOffsetDp(0f), 0.0001f)
        assertEquals(0f, AppVolumeBarHit.internalOverflowOffsetDp(100f), 0.0001f)
        assertTrue(AppVolumeBarHit.internalOverflowOffsetDp(110f) > 0f)
        assertTrue(AppVolumeBarHit.internalOverflowOffsetDp(-8f) < 0f)
        assertTrue(
            AppVolumeBarHit.internalOverflowOffsetDp(10_000f) <=
                    AppVolumeBarHit.INTERNAL_OVERFLOW_MAX_DP,
        )
        assertTrue(
            AppVolumeBarHit.internalOverflowOffsetDp(-10_000f) >=
                    -AppVolumeBarHit.INTERNAL_OVERFLOW_MAX_DP,
        )
    }

    @Test
    fun fillFractionClampsOverflow() {
        assertEquals(0f, AppVolumeBarHit.fillFraction(-8f), 0.0001f)
        assertEquals(0.4f, AppVolumeBarHit.fillFraction(40f), 0.0001f)
        assertEquals(1f, AppVolumeBarHit.fillFraction(118f), 0.0001f)
    }

    @Test
    fun relativeDragDoesNotJumpToTouchPosition() {
        val width = 200f
        val touchAtRight = width * 0.9f

        assertEquals(15f, AppVolumeBarHit.volumeFromRelativeDrag(15, 0f, width), 0.0001f)
        assertEquals(25f, AppVolumeBarHit.volumeFromRelativeDrag(15, width * 0.1f, width), 0.0001f)
        assertFalse(
            AppVolumeBarHit.volumeFromRelativeDrag(
                15,
                0f,
                width
            ) == touchAtRight / width * 100f
        )
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
    fun progressKeepsFixedSmallRightRadiusAtZeroMiddleAndFull() {
        val zero = AppVolumeBarHit.fillGeometry(0f)
        val middle = AppVolumeBarHit.fillGeometry(0.5f)
        val full = AppVolumeBarHit.fillGeometry(1f)

        assertFalse(zero.visible)
        assertTrue(middle.visible)
        assertTrue(full.visible)
        assertEquals(AppVolumeBarHit.FILL_END_RADIUS_DP, zero.rightRadiusDp, 0f)
        assertEquals(AppVolumeBarHit.FILL_END_RADIUS_DP, middle.rightRadiusDp, 0f)
        assertEquals(AppVolumeBarHit.FILL_END_RADIUS_DP, full.rightRadiusDp, 0f)
        assertTrue(full.rightRadiusDp < full.leftRadiusDp)
        assertEquals(60f, AppVolumeBarHit.SHELL_HEIGHT_DP, 0f)
    }

    @Test
    fun borderIsAlwaysTheLastVolumeBarLayer() {
        assertEquals(VolumeBarLayer.Track, VolumeBarLayerOrder.first())
        assertTrue(
            VolumeBarLayerOrder.indexOf(VolumeBarLayer.Progress) < VolumeBarLayerOrder.indexOf(
                VolumeBarLayer.Icons
            )
        )
        assertEquals(VolumeBarLayer.Border, VolumeBarLayerOrder.last())
    }

    @Test
    fun rejectsNonPositiveWidth() {
        assertThrows(IllegalArgumentException::class.java) {
            AppVolumeBarHit.isMoreHit(0f, 0f)
        }
        assertThrows(IllegalArgumentException::class.java) {
            AppVolumeBarHit.volumeFromRelativeDrag(40, 0f, 0f)
        }
        assertThrows(IllegalArgumentException::class.java) {
            AppVolumeBarHit.isMoreHit(0f, -1f)
        }
        assertThrows(IllegalArgumentException::class.java) {
            AppVolumeBarHit.volumeFromRelativeDrag(40, 10f, -8f)
        }
        assertThrows(IllegalArgumentException::class.java) {
            AppVolumeBarHit.volumeFromRelativeDrag(140, 0f, 200f)
        }
        assertThrows(IllegalArgumentException::class.java) {
            AppVolumeBarHit.volumeFromRelativeDrag(20, Float.NaN, 200f)
        }
        assertThrows(IllegalArgumentException::class.java) {
            AppVolumeBarHit.moreIconFillCoverage(-0.1f)
        }
        assertThrows(IllegalArgumentException::class.java) {
            AppVolumeBarHit.isDragPastSlop(1f, 0f)
        }
        assertThrows(IllegalArgumentException::class.java) {
            AppVolumeBarHit.rubberBand(50f, min = 10f, max = 10f)
        }
        assertThrows(IllegalArgumentException::class.java) {
            AppVolumeBarHit.rubberBandOverflow(-1f, 100f, 0.55f)
        }
        assertThrows(IllegalArgumentException::class.java) {
            AppVolumeBarHit.internalOverflowOffsetDp(Float.NaN)
        }
        assertThrows(IllegalArgumentException::class.java) {
            AppVolumeBarHit.fillGeometry(1.01f)
        }
    }
}
