package hk.uwu.soundman.data

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InstalledAppsAccessTest {
    private val context = Application()

    @Test
    fun miuiPermissionDeniedIsRuntimePresentWithoutAccess() {
        val access = InstalledAppsAccess(
            catalog(owner = MIUI_OWNER, granted = false),
            warn = { _, _ -> },
        )

        assertTrue(access.isRuntimePermissionPresent())
        assertFalse(access.hasAccess(context))
        assertEquals(GET_INSTALLED_APPS, access.permissionName())
    }

    @Test
    fun miuiPermissionGrantedHasAccess() {
        val access = InstalledAppsAccess(
            catalog(owner = MIUI_OWNER, granted = true),
            warn = { _, _ -> },
        )

        assertTrue(access.isRuntimePermissionPresent())
        assertTrue(access.hasAccess(context))
    }

    @Test
    fun missingPermissionIsNotRuntimeAndHasAccess() {
        val access = InstalledAppsAccess(
            catalog(owner = null, granted = false),
            warn = { _, _ -> },
        )

        assertFalse(access.isRuntimePermissionPresent())
        assertTrue(access.hasAccess(context))
    }

    @Test
    fun foreignOwnerIsNotMiuiRuntimePermission() {
        val access = InstalledAppsAccess(
            catalog(owner = "com.android.permissioncontroller", granted = false),
            warn = { _, _ -> },
        )

        assertFalse(access.isRuntimePermissionPresent())
        assertTrue(access.hasAccess(context))
        assertEquals(GET_INSTALLED_APPS, access.permissionName())
    }

    private fun catalog(owner: String?, granted: Boolean): PermissionCatalog =
        PermissionCatalog(
            ownerOf = { permission ->
                check(permission == GET_INSTALLED_APPS) { "unexpected permission $permission" }
                owner
            },
            grantedOf = { permission ->
                check(permission == GET_INSTALLED_APPS) { "unexpected permission $permission" }
                granted
            },
        )

    private companion object {
        const val GET_INSTALLED_APPS = "com.android.permission.GET_INSTALLED_APPS"
        const val MIUI_OWNER = "com.lbe.security.miui"
    }
}
