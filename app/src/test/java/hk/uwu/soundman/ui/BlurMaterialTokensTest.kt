package hk.uwu.soundman.ui

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BlurMaterialTokensTest {
    @Test
    fun everyMaterialPurposeHasBoundedStableGlassParameters() {
        BlurMaterialPurpose.entries.forEach { purpose ->
            val spec = BlurMaterialTokens.spec(purpose)
            assertTrue("blur radius for $purpose", spec.blurRadius in 0.1f..150f)
            assertTrue("tint alpha for $purpose", spec.tintAlpha in 0f..1f)
            assertTrue("contrast for $purpose", spec.contrast > 0f)
            assertTrue("saturation for $purpose", spec.saturation > 0f)
            assertTrue("noise for $purpose", spec.noise in 0f..0.02f)
            assertTrue("soft light for $purpose", spec.softLightAlpha in 0f..1f)
        }
    }

    @Test
    fun everyMajorSurfacePurposeFollowsUnifiedMatchingShapePolicy() {
        val majorPurposes = setOf(
            BlurMaterialPurpose.Card,
            BlurMaterialPurpose.Action,
            BlurMaterialPurpose.Panel,
            BlurMaterialPurpose.Hint,
            BlurMaterialPurpose.DeviceRow,
            BlurMaterialPurpose.DeviceSelected,
            BlurMaterialPurpose.VolumeTrack,
            BlurMaterialPurpose.VolumeFill,
        )
        assertEquals(majorPurposes, BlurMaterialPurpose.entries.toSet())

        majorPurposes.forEach { purpose ->
            val regular = GlassShapeTokens.policy(purpose, smoothCornersEnabled = false)
            val smooth = GlassShapeTokens.policy(purpose, smoothCornersEnabled = true)

            assertEquals(GlassOutlineKind.Rounded, regular.clip)
            assertEquals(regular.clip, regular.border)
            assertEquals(GlassOutlineKind.Squircle, smooth.clip)
            assertEquals(smooth.clip, smooth.border)
            assertEquals(1, regular.finalClipCount)
            assertEquals(1, smooth.finalClipCount)
            assertTrue(regular.surfaceTintInsideFinalClip)
            assertTrue(smooth.surfaceTintInsideFinalClip)
            assertEquals(1.1f, regular.squircleExtension, 0f)
            assertEquals(regular.squircleExtension, smooth.squircleExtension, 0f)
            assertTrue(regular.usesOfficialSurfaceAndBorder)
            assertTrue(smooth.usesOfficialSurfaceAndBorder)

            val regularGeometry = GlassShapeTokens.geometry(
                purpose = purpose,
                smoothCornersEnabled = false,
                cornerRadius = 28f,
                borderWidth = 1f,
            )
            val smoothGeometry = GlassShapeTokens.geometry(
                purpose = purpose,
                smoothCornersEnabled = true,
                cornerRadius = 28f,
                borderWidth = 1f,
            )
            listOf(regularGeometry, smoothGeometry).forEach { geometry ->
                assertEquals(geometry.surfaceCornerRadius, geometry.borderCornerRadius, 0f)
                assertEquals(0.5f, geometry.borderStrokeInset, 0f)
                assertEquals(1, geometry.finalClipCount)
            }
            assertEquals(regularGeometry.squircleExtension, smoothGeometry.squircleExtension, 0f)
        }
    }

    @Test
    fun ordinarySurfacesUseStableSharedGlassFill() {
        val ordinaryPurposes = setOf(
            BlurMaterialPurpose.Card,
            BlurMaterialPurpose.Action,
            BlurMaterialPurpose.Panel,
            BlurMaterialPurpose.DeviceRow,
            BlurMaterialPurpose.VolumeTrack,
        )
        ordinaryPurposes.forEach { purpose ->
            assertEquals(OverlayGlassFill, BlurHostTokens.surfaceFill(Color.Transparent, purpose))
        }
    }

    @Test
    fun semanticSurfacesPreserveRequestedFill() {
        val hint = Color(0x663366FF)
        val selected = Color(0x997755FF)
        assertEquals(hint, BlurHostTokens.surfaceFill(hint, BlurMaterialPurpose.Hint))
        assertEquals(
            selected,
            BlurHostTokens.surfaceFill(selected, BlurMaterialPurpose.DeviceSelected)
        )
        assertFalse(BlurHostTokens.isOrdinarySurface(BlurMaterialPurpose.VolumeFill))
    }
}
