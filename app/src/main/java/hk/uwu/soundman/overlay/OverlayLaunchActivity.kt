package hk.uwu.soundman.overlay

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.net.toUri
import hk.uwu.soundman.R
import hk.uwu.soundman.log.AppLog

/**
 * 音量侧栏打开浮层的透明跳板。
 *
 * 独立 taskAffinity，不加入 [hk.uwu.soundman.MainActivity] 的主页任务，
 * 因此后台里若还留着主页，也不会被拉到半透明浮层后面。
 */
class OverlayLaunchActivity : ComponentActivity() {
    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        if (Settings.canDrawOverlays(this)) {
            showSystemOverlay()
        } else {
            AppLog.error("Overlay permission was not granted")
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.Theme_SoundMan_Overlay)
        super.onCreate(savedInstanceState)
        splashScreen.setOnExitAnimationListener { it.remove() }
        window.setBackgroundDrawableResource(android.R.color.transparent)
        requestOverlay()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        requestOverlay()
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
        finish()
    }
}
