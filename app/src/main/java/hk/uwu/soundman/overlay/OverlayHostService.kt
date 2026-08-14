package hk.uwu.soundman.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.activity.setViewTreeOnBackPressedDispatcherOwner
import androidx.compose.ui.platform.ComposeView
import androidx.core.app.NotificationCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import hk.uwu.soundman.MainActivity
import hk.uwu.soundman.R
import hk.uwu.soundman.log.AppLog
import hk.uwu.soundman.ui.SoundPanel

private const val CHANNEL_ID = "soundman_overlay"
private const val NOTIFICATION_ID = 1108

/**
 * TYPE_APPLICATION_OVERLAY 宿主。它仅承载 Compose UI，不接触音频流或全局媒体音量。
 */
class OverlayHostService : Service() {
    private val windowManager by lazy { getSystemService(WindowManager::class.java) }
    private var panelView: ComposeView? = null
    private var composeOwner: OverlayComposeOwner? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action ?: ACTION_SHOW) {
            ACTION_HIDE -> stopSelf()
            ACTION_SHOW -> {
                startForeground(NOTIFICATION_ID, createNotification())
                if (!Settings.canDrawOverlays(this)) {
                    AppLog.error("Cannot show panel without SYSTEM_ALERT_WINDOW permission")
                    stopSelf()
                    return START_NOT_STICKY
                }
                showPanel(OverlayOpenRequest.fromIntent(intent).fromVolumeSidebar)
            }
            else -> {
                AppLog.error("Unsupported overlay action: ${intent?.action}")
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        removePanel()
        super.onDestroy()
    }

    private fun showPanel(fromVolumeSidebar: Boolean) {
        if (panelView != null) return
        val owner = OverlayComposeOwner(onBack = ::stopSelf).also { it.start() }
        val view = ComposeView(this).apply {
            isFocusableInTouchMode = true
            setOnKeyListener { _, keyCode, event ->
                if (keyCode != KeyEvent.KEYCODE_BACK) {
                    false
                } else {
                    if (event.action == KeyEvent.ACTION_UP) {
                        owner.onBackPressedDispatcher.onBackPressed()
                    }
                    true
                }
            }
            setOnTouchListener { _, event ->
                if (event.action == android.view.MotionEvent.ACTION_OUTSIDE) {
                    stopSelf()
                    true
                } else {
                    false
                }
            }
            setViewTreeLifecycleOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)
            setViewTreeViewModelStoreOwner(owner)
            setViewTreeOnBackPressedDispatcherOwner(owner)
            setContent {
                SoundPanel(
                    context = this@OverlayHostService,
                    onDismiss = ::stopSelf,
                    onRequestInstalledAppsPermission = ::openMainActivityForInstalledAppsPermission,
                    fromVolumeSidebar = fromVolumeSidebar,
                )
            }
        }
        val flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
            WindowManager.LayoutParams.FLAG_DIM_BEHIND
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
            } else {
                flags
            },
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.CENTER
            dimAmount = 0.45f
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setBlurBehindRadius(80)
            }
        }
        try {
            windowManager.addView(view, params)
            composeOwner = owner
            panelView = view
        } catch (error: WindowManager.BadTokenException) {
            owner.destroy()
            AppLog.error("WindowManager rejected overlay token", error)
            stopSelf()
        } catch (error: SecurityException) {
            owner.destroy()
            AppLog.error("WindowManager denied TYPE_APPLICATION_OVERLAY", error)
            stopSelf()
        }
    }

    private fun removePanel() {
        val view = panelView
        if (view != null) {
            try {
                windowManager.removeView(view)
            } catch (error: IllegalArgumentException) {
                AppLog.error("Overlay view was already detached", error)
            }
        }
        panelView = null
        composeOwner?.destroy()
        composeOwner = null
    }

    private fun openMainActivityForInstalledAppsPermission() {
        startActivity(
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.overlay_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.overlay_channel_description)
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun createNotification(): Notification {
        val hideIntent = Intent(this, OverlayHostService::class.java).setAction(ACTION_HIDE)
        val pendingHide = android.app.PendingIntent.getService(
            this,
            0,
            hideIntent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.overlay_notification_title))
            .setContentText(getString(R.string.overlay_notification_text))
            .setOngoing(true)
            .setSilent(true)
            .addAction(0, getString(R.string.overlay_close), pendingHide)
            .build()
    }

    companion object {
        const val ACTION_SHOW = "hk.uwu.soundman.action.SHOW_OVERLAY"
        const val ACTION_HIDE = "hk.uwu.soundman.action.HIDE_OVERLAY"
    }
}

/** 为脱离 Activity 的 ComposeView 提供明确生命周期。 */
private class OverlayComposeOwner(
    onBack: () -> Unit,
) : LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner, OnBackPressedDispatcherOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)
    private val backDispatcher = OnBackPressedDispatcher(onBack)

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateController.savedStateRegistry

    override val viewModelStore: ViewModelStore = ViewModelStore()

    override val onBackPressedDispatcher: OnBackPressedDispatcher
        get() = backDispatcher

    fun start() {
        savedStateController.performAttach()
        savedStateController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.STARTED
    }

    fun destroy() {
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        viewModelStore.clear()
    }
}
