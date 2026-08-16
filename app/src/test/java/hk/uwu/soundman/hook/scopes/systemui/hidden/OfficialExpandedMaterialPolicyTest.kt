package hk.uwu.soundman.hook.scopes.systemui.hidden

import org.junit.Assert.assertEquals
import org.junit.Test

class OfficialExpandedMaterialPolicyTest {
    @Test
    fun advancedMaterialAlwaysUsesOfficialGlassPath() {
        assertEquals(
            OfficialExpandedMaterialMode.ADVANCED,
            OfficialExpandedMaterialPolicy.choose(
                advancedMaterialEffective = true,
                lowEndDevice = true,
                defaultPluginTheme = false,
            ),
        )
    }

    @Test
    fun nonAdvancedUsesSBlurOnlyForEligiblePluginTheme() {
        assertEquals(
            OfficialExpandedMaterialMode.BLUR_FOR_S,
            OfficialExpandedMaterialPolicy.choose(
                advancedMaterialEffective = false,
                lowEndDevice = false,
                defaultPluginTheme = true,
            ),
        )
        assertEquals(
            OfficialExpandedMaterialMode.STATIC,
            OfficialExpandedMaterialPolicy.choose(
                advancedMaterialEffective = false,
                lowEndDevice = true,
                defaultPluginTheme = true,
            ),
        )
        assertEquals(
            OfficialExpandedMaterialMode.STATIC,
            OfficialExpandedMaterialPolicy.choose(
                advancedMaterialEffective = false,
                lowEndDevice = false,
                defaultPluginTheme = false,
            ),
        )
    }
}
