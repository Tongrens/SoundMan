package hk.uwu.soundman

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.net.toUri
import hk.uwu.soundman.data.APP_SETTINGS_PREFERENCES_NAME
import hk.uwu.soundman.data.InstalledAppsAccess
import hk.uwu.soundman.data.PermissionCatalog
import hk.uwu.soundman.data.SharedPreferencesAppSettingsStore
import hk.uwu.soundman.data.SystemUiAppSettingsSync
import hk.uwu.soundman.log.AppLog
import hk.uwu.soundman.overlay.OverlayHostService
import hk.uwu.soundman.overlay.OverlayOpenRequest
import hk.uwu.soundman.ui.HomeScreen

/**
 * 模块主页。音量调节只出现在悬浮窗；侧栏入口走 [hk.uwu.soundman.overlay.OverlayLaunchActivity]，不经过本页。
 */
class MainActivity : ComponentActivity() {
    private var finishAfterOverlay = false
    private var homeVisible = false

    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        if (Settings.canDrawOverlays(this)) {
            showSystemOverlay()
        } else {
            AppLog.error("Overlay permission was not granted")
            if (finishAfterOverlay && !homeVisible) finish()
        }
    }

    private val installedAppsPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        AppLog.info("GET_INSTALLED_APPS granted=$granted")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        if (intent?.action == ACTION_OPEN_OVERLAY) {
            setTheme(R.style.Theme_SoundMan_Overlay)
        }
        super.onCreate(savedInstanceState)
        splashScreen.setOnExitAnimationListener { it.remove() }
        window.setBackgroundDrawableResource(android.R.color.transparent)
        if (intent?.action == ACTION_OPEN_OVERLAY) {
            finishAfterOverlay = true
            requestOverlay()
            return
        }
        enableEdgeToEdge()
        maybeRequestInstalledAppsPermission()
        homeVisible = true
        val settingsStore = SharedPreferencesAppSettingsStore(
            preferences = getSharedPreferences(APP_SETTINGS_PREFERENCES_NAME, MODE_PRIVATE),
            systemUiBuiltinPanelMirror = { enabled ->
                SystemUiAppSettingsSync.persistBuiltinPanelEnabled(this, enabled)
            },
            hideSystemAppsMirror = { enabled ->
                SystemUiAppSettingsSync.persistHideSystemAppsEnabled(this, enabled)
            },
        )
        try {
            SystemUiAppSettingsSync.persistBuiltinPanelEnabled(
                this,
                settingsStore.read().systemUiBuiltinVolumePanelEnabled,
            )
        } catch (error: RuntimeException) {
            AppLog.error(
                "Unable to synchronize SystemUI builtin panel setting during startup",
                error
            )
        }
        try {
            SystemUiAppSettingsSync.persistHideSystemAppsEnabled(
                this,
                settingsStore.read().hideSystemAppsEnabled,
            )
        } catch (error: RuntimeException) {
            AppLog.error(
                "Unable to synchronize hide-system-apps setting during startup",
                error
            )
        }
        setContent {
            HomeScreen(
                settingsStore = settingsStore,
                onOpenOverlay = ::requestOverlay,
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == ACTION_OPEN_OVERLAY) {
            OverlayHostService.startShow(this, OverlayOpenRequest.fromIntent(intent))
            // 主页任务若已被拉到前台，立刻退到后台，避免半透明浮层后面露出主屏。
            moveTaskToBack(true)
        }
    }

    private fun maybeRequestInstalledAppsPermission() {
        val installedAppsAccess = InstalledAppsAccess(PermissionCatalog(this))
        if (!installedAppsAccess.isRuntimePermissionPresent()) return
        if (installedAppsAccess.hasAccess(this)) return
        installedAppsPermissionLauncher.launch(installedAppsAccess.permissionName())
    }

    private fun requestOverlay() {
        if (Settings.canDrawOverlays(this)) {
            showSystemOverlay()
            return
        }
        overlayPermissionLauncher.launch(
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                "package:$packageName".toUri(),
            ),
        )
    }

    private fun showSystemOverlay() {
        OverlayHostService.startShow(this, OverlayOpenRequest.fromIntent(intent))
        if (finishAfterOverlay && !homeVisible) finish()
    }

    companion object {
        const val ACTION_OPEN_OVERLAY = OverlayOpenRequest.ACTION_OPEN_OVERLAY
    }
}
