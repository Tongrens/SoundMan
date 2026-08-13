package hk.uwu.soundman

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import hk.uwu.soundman.data.InstalledAppsAccess
import hk.uwu.soundman.data.PermissionCatalog
import hk.uwu.soundman.overlay.OverlayHostService
import hk.uwu.soundman.overlay.OverlayOpenRequest
import hk.uwu.soundman.ui.SoundPanel

private const val TAG = "SoundManActivity"

/**
 * 透明浮层式入口。普通应用进程只负责面板、规则持久化与悬浮窗授权。
 */
class MainActivity : ComponentActivity() {
    private var finishAfterPermissionResult = false
    private var showPanel by mutableStateOf(false)
    private var installedAppsPermissionRevision by mutableIntStateOf(0)

    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        if (Settings.canDrawOverlays(this)) {
            showSystemOverlay()
        } else {
            Log.e(TAG, "Overlay permission was not granted")
            if (finishAfterPermissionResult) finish()
        }
    }

    private val installedAppsPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        Log.i(TAG, "GET_INSTALLED_APPS granted=$granted")
        installedAppsPermissionRevision++
        showPanel = true
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent?.action == ACTION_OPEN_OVERLAY) {
            finishAfterPermissionResult = true
            requestOverlay()
            return
        }
        configureFloatingWindow()
        val installedAppsAccess = InstalledAppsAccess(PermissionCatalog(this))
        if (installedAppsAccess.isRuntimePermissionPresent() && !installedAppsAccess.hasAccess(this)) {
            installedAppsPermissionLauncher.launch(installedAppsAccess.permissionName())
        } else {
            showPanel = true
        }
        setContent {
            if (!showPanel) return@setContent
            val permissionRevision = installedAppsPermissionRevision
            SoundPanel(
                context = this,
                onRequestOverlay = ::requestOverlay,
                onDismiss = ::finish,
                onRequestInstalledAppsPermission = ::requestInstalledAppsPermission,
                installedAppsPermissionRevision = permissionRevision,
            )
        }
    }

    private fun requestInstalledAppsPermission() {
        val installedAppsAccess = InstalledAppsAccess(PermissionCatalog(this))
        if (!installedAppsAccess.isRuntimePermissionPresent()) return
        if (installedAppsAccess.hasAccess(this)) {
            installedAppsPermissionRevision++
            return
        }
        installedAppsPermissionLauncher.launch(installedAppsAccess.permissionName())
    }

    private fun configureFloatingWindow() {
        window.setBackgroundDrawableResource(android.R.color.transparent)
        window.setDimAmount(0.45f)
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
        }
        window.attributes = window.attributes.apply {
            gravity = Gravity.CENTER
            width = WindowManager.LayoutParams.MATCH_PARENT
            height = WindowManager.LayoutParams.MATCH_PARENT
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setBlurBehindRadius(80)
            }
        }
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
    }

    private fun requestOverlay() {
        if (Settings.canDrawOverlays(this)) {
            showSystemOverlay()
            return
        }
        overlayPermissionLauncher.launch(
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName"),
            ),
        )
    }

    private fun showSystemOverlay() {
        val overlayIntent = Intent(this, OverlayHostService::class.java)
            .setAction(OverlayHostService.ACTION_SHOW)
        OverlayOpenRequest.fromIntent(intent).putInto(overlayIntent)
        startForegroundService(overlayIntent)
        finish()
    }

    companion object {
        const val ACTION_OPEN_OVERLAY = "hk.uwu.soundman.action.OPEN_OVERLAY"
    }
}
