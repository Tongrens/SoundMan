package hk.uwu.soundman.hook.scopes.systemui.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemUiSecurityPolicyTest {
    @Test
    fun moduleUidIsAllowedWithoutSystemPackages() {
        assertTrue(SystemUiSecurityPolicy.isModuleOrSystemUi(12345, 12345, 1000, emptySet()))
    }

    @Test
    fun verifiedCallingPackageMustBeSystemUi() {
        val packages = setOf("android", SystemUiSecurityPolicy.SYSTEM_UI_PACKAGE)
        assertFalse(
            SystemUiSecurityPolicy.isModuleOrSystemUi(
                1000,
                12345,
                1000,
                packages,
                "android"
            )
        )
        assertTrue(
            SystemUiSecurityPolicy.isModuleOrSystemUi(
                1000,
                12345,
                1000,
                packages,
                SystemUiSecurityPolicy.SYSTEM_UI_PACKAGE,
            ),
        )
    }

    @Test
    fun generationGateRejectsCloseAndLateResults() {
        assertTrue(
            SystemUiGenerationGate.accepts(
                closed = false,
                currentGeneration = 4L,
                resultGeneration = 4L
            )
        )
        assertFalse(
            SystemUiGenerationGate.accepts(
                closed = true,
                currentGeneration = 4L,
                resultGeneration = 4L
            )
        )
        assertFalse(
            SystemUiGenerationGate.accepts(
                closed = false,
                currentGeneration = 5L,
                resultGeneration = 4L
            )
        )
    }

    @Test
    fun overlayFallbackOnlyRunsOnceForCurrentLiveGeneration() {
        assertTrue(SystemUiFallbackPolicy.shouldRequest(false, 7L, 7L, false))
        assertFalse(SystemUiFallbackPolicy.shouldRequest(false, 7L, 7L, true))
        assertFalse(SystemUiFallbackPolicy.shouldRequest(true, 7L, 7L, false))
        assertFalse(SystemUiFallbackPolicy.shouldRequest(false, 8L, 7L, false))
    }
}
