package hk.uwu.soundman.hook.scopes.systemui.runtime

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.UserHandle
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import hk.uwu.soundman.R
import hk.uwu.soundman.data.AudioDeviceScan
import hk.uwu.soundman.data.PanelPlaybackRow
import hk.uwu.soundman.data.PanelPlaybackSnapshot
import hk.uwu.soundman.data.PanelPlaybackStatus
import hk.uwu.soundman.data.ProviderPanelPlayback
import hk.uwu.soundman.hook.scopes.systemui.hidden.OfficialExpandedMaterial
import hk.uwu.soundman.model.AudioOutputDevice
import hk.uwu.soundman.model.OutputDeviceType
import hk.uwu.soundman.model.OutputTarget
import hk.uwu.soundman.overlay.OverlayOpenRequest
import hk.uwu.soundman.ui.DevicePageRow
import hk.uwu.soundman.ui.DevicePageRowKind
import hk.uwu.soundman.ui.DevicePageRows
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.WeakHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** 在 MiuiVolumeDialogView 旁挂载独立应用音量页，不触碰官方展开状态或内部 View 树。 */
class SystemUiBuiltinVolumePanel(
    private val log: (priority: Int, tag: String, message: String, throwable: Throwable?) -> Unit,
    private val hookDismiss: () -> Boolean,
) {
    fun closeFor(sourceView: View) {
        try {
            val dialog = findDialog(sourceView) ?: return
            synchronized(sessions) { sessions[dialog] }?.close("entry cleanup")
        } catch (throwable: Throwable) {
            log(Log.ERROR, TAG, "Independent panel close boundary failed", throwable)
        }
    }

    fun mount(sourceView: View, openOverlay: () -> Unit): Boolean {
        val dialog = findDialog(sourceView) ?: run {
            log(
                Log.ERROR,
                TAG,
                "Independent panel mount failed: MiuiVolumeDialogView not found",
                null
            )
            return false
        }
        synchronized(sessions) { sessions[dialog]?.let { return true } }
        var session: Session? = null
        return try {
            val targetContext = dialog.context
            val created = Session(
                dialog = dialog,
                targetContext = targetContext,
                moduleContext = ModuleApplicationContext(
                    targetContext.createPackageContext(
                        OverlayOpenRequest.MODULE_PACKAGE,
                        Context.CONTEXT_IGNORE_SECURITY,
                    ),
                ),
                openOverlay = openOverlay,
                log = log,
                hookDismiss = hookDismiss,
                onClosed = { closedSession ->
                    synchronized(sessions) {
                        if (sessions[dialog] === closedSession) sessions.remove(dialog)
                    }
                },
            )
            session = created
            created.mount()
            synchronized(sessions) { sessions[dialog] = created }
            true
        } catch (throwable: Throwable) {
            try {
                session?.closeImmediately("mount failure")
            } catch (cleanupError: Throwable) {
                log(Log.ERROR, TAG, "Independent panel mount cleanup failed", cleanupError)
            }
            log(
                Log.ERROR,
                TAG,
                "Independent panel mount failed; falling back to overlay",
                throwable
            )
            false
        }
    }

    private fun findDialog(source: View): ViewGroup? {
        var current: View? = source
        while (current != null) {
            if (current.javaClass.name == VOLUME_DIALOG_VIEW_CLASS) return current as? ViewGroup
            current = current.parent as? View
        }
        return null
    }

    private class ModuleApplicationContext(base: Context) : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
    }

    private class Session(
        private val dialog: ViewGroup,
        private val targetContext: Context,
        private val moduleContext: Context,
        private val openOverlay: () -> Unit,
        private val log: (priority: Int, tag: String, message: String, throwable: Throwable?) -> Unit,
        private val hookDismiss: () -> Boolean,
        private val onClosed: (Session) -> Unit,
    ) {
        private val closed = AtomicBoolean(false)
        private val fallbackRequested = AtomicBoolean(false)
        private val generation = AtomicLong(0L)
        private val executor: ScheduledExecutorService =
            Executors.newSingleThreadScheduledExecutor { runnable ->
                Thread(runnable, "SoundMan.PanelBridge").apply { isDaemon = true }
            }
        private val panelBridge = ProviderPanelPlayback(targetContext)
        private val deviceRows = DevicePageRows()
        private val originalVisibility = dialog.visibility
        private val originalAlpha = dialog.alpha
        private val originalImportantForAccessibility = dialog.importantForAccessibility
        private val officialColumns = ArrayList<OfficialVolumeColumn>()
        private val trackingUids = HashSet<Int>()
        private val appVisualCache = HashMap<String, Pair<String, Drawable>>()
        private val pluginDrawableIdCache = HashMap<String, Int>()
        private lateinit var windowRoot: ViewGroup
        private lateinit var host: FrameLayout
        private lateinit var panel: FrameLayout
        private lateinit var pageHost: FrameLayout
        private lateinit var animationSpec: SystemUiIndependentPanelAnimationSpec
        private lateinit var foldedRect: SystemUiPanelRect
        private lateinit var pluginClassLoader: ClassLoader
        private lateinit var expandedMaterial: OfficialExpandedMaterial
        private val touchInsets = OfficialTouchInsetsRegistration(dialog, log)
        private var morphAnimator: ValueAnimator? = null
        private var resizeAnimator: ValueAnimator? = null
        private var slideAwayAnimator: ValueAnimator? = null
        private var animationFraction = 0f
        private var openAnimationStarted = false
        private var currentPage: View? = null
        private var selectedPackage: String? = null
        private var lastSnapshot: PanelPlaybackSnapshot? = null
        private var lastFingerprint: SystemUiPanelSnapshotFingerprint? = null
        private var fallbackColumnWidth = 0
        private var fallbackColumnHeight = 0

        fun mount() {
            check(!closed.get()) { "Cannot mount a closed independent panel" }
            windowRoot =
                dialog.rootView as? ViewGroup ?: error("Volume window root is not a ViewGroup")
            check(windowRoot.findViewWithTag<View>(HOST_TAG) == null) { "Independent full-window host already exists" }
            pluginClassLoader = dialog.javaClass.classLoader
                ?: error("MiuiVolumeDialogView has no plugin ClassLoader")

            val foldedWidth =
                dimension(dialog.width, dialog.measuredWidth, dialog.layoutParams?.width)
            val foldedHeight =
                dimension(dialog.height, dialog.measuredHeight, dialog.layoutParams?.height)
            val dialogLocation = IntArray(2).also(dialog::getLocationOnScreen)
            val rootLocation = IntArray(2).also(windowRoot::getLocationOnScreen)
            foldedRect = SystemUiPanelRect(
                left = dialogLocation[0] - rootLocation[0],
                top = dialogLocation[1] - rootLocation[1],
                right = dialogLocation[0] - rootLocation[0] + foldedWidth,
                bottom = dialogLocation[1] - rootLocation[1] + foldedHeight,
            )
            fallbackColumnWidth = (foldedWidth - dp(PANEL_HORIZONTAL_PADDING_DP) * 2)
                .coerceIn(dp(MIN_COLUMN_WIDTH_DP), dp(MAX_COLUMN_WIDTH_DP))
            fallbackColumnHeight = (foldedHeight - dp(PANEL_VERTICAL_PADDING_DP) * 2)
                .coerceAtLeast(dp(MIN_COLUMN_HEIGHT_DP))

            expandedMaterial = OfficialExpandedMaterial(pluginClassLoader, targetContext, log)
            host = buildFullWindowHost()
            panel = buildPanel()
            expandedMaterial.applyOutline(panel)
            panel.alpha = 0f
            panel.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) {
                    try {
                        expandedMaterial.apply(panel)
                    } catch (throwable: Throwable) {
                        log(
                            Log.ERROR,
                            TAG,
                            "Official expanded material failed after panel attach",
                            throwable
                        )
                        closeImmediately("material failure")
                    }
                }

                override fun onViewDetachedFromWindow(v: View) {
                    if (!closed.get()) closeImmediately("panel detached")
                }
            })
            host.addView(
                panel,
                FrameLayout.LayoutParams(
                    foldedRect.width,
                    foldedRect.height,
                    Gravity.TOP or Gravity.START
                ).apply {
                    leftMargin = foldedRect.left
                    topMargin = foldedRect.top
                },
            )
            touchInsets.pause()
            windowRoot.addView(
                host, ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
            )
            dialog.alpha = 0f
            dialog.visibility = View.INVISIBLE
            dialog.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            startPolling()
            log(
                Log.INFO,
                TAG,
                "Mounted independent full-window host folded=${foldedWidth}x$foldedHeight " +
                        "root=${rootWidth()}x${rootHeight()} class=${windowRoot.javaClass.name}",
                null,
            )
        }

        private fun buildFullWindowHost(): FrameLayout = FrameLayout(targetContext).apply {
            tag = HOST_TAG
            isClickable = true
            isFocusable = false
            background = null
            clipChildren = false
            clipToPadding = false
            var outsideGesture = false
            setOnTouchListener { _, event ->
                val currentPanel = if (::panel.isInitialized) currentPanelRect() else foldedRect
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        outsideGesture = SystemUiIndependentPanelPolicy.hitTest(
                            currentPanel,
                            event.x.toInt(),
                            event.y.toInt(),
                        ) == SystemUiPanelHit.OUTSIDE
                        true
                    }

                    MotionEvent.ACTION_UP -> {
                        val shouldClose = outsideGesture
                        outsideGesture = false
                        // 点击面板外部关闭要走带动画的折叠（morph 回折叠尺寸），
                        // 不要用 closeImmediately 瞬闪消失，否则用户会觉得“没有关闭动画”。
                        if (shouldClose) close("outside touch")
                        true
                    }

                    MotionEvent.ACTION_CANCEL -> {
                        outsideGesture = false
                        true
                    }

                    else -> true
                }
            }
        }

        private fun buildPanel(): FrameLayout = FrameLayout(targetContext).apply {
            tag = PANEL_TAG
            isClickable = true
            isFocusable = true
            background = null
            outlineProvider = dialog.outlineProvider
            clipToOutline = true
            // 不继承官方 dialog 的 elevation/translationZ：全窗口 host 上再叠一层投影会形成
            // 灰色阴影边框，官方展开面板背景（expandedMaterial）自带圆角材质，无需投影。
            elevation = 0f
            translationZ = 0f
            clipChildren = true
            clipToPadding = true
            pageHost = FrameLayout(targetContext).apply {
                isClickable = true
                clipChildren = true
                clipToPadding = true
            }
            addView(
                pageHost,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
        }

        private fun startOpenAnimation() {
            if (openAnimationStarted) return
            openAnimationStarted = true
            startMorphAnimation(from = 0f, to = 1f, onEnd = null)
        }

        private fun startMorphAnimation(from: Float, to: Float, onEnd: (() -> Unit)?) {
            morphAnimator?.cancel()
            morphAnimator = ValueAnimator.ofFloat(from, to).apply {
                duration = EXPAND_ANIMATION_DURATION_MILLIS
                interpolator = EXPAND_INTERPOLATOR
                addUpdateListener { animator ->
                    val fraction = animator.animatedValue as Float
                    animationFraction = fraction
                    applyPanelRect(
                        SystemUiIndependentPanelPolicy.interpolateRect(
                            animationSpec,
                            fraction
                        )
                    )
                    // 整个面板内容（音量条 + 更多按钮 + 应用图标）作为整体淡入，不做列分层；
                    // 列分层会让音量条与按钮/图标不同步播放，用户要求三者整体连贯。
                    panel.alpha = fraction
                }
                addListener(object : AnimatorListenerAdapter() {
                    private var cancelled = false

                    override fun onAnimationCancel(animation: Animator) {
                        cancelled = true
                    }

                    override fun onAnimationEnd(animation: Animator) {
                        if (!cancelled) onEnd?.invoke()
                    }
                })
                start()
            }
        }

        private fun applyPanelRect(rect: SystemUiPanelRect) {
            val params = panel.layoutParams ?: return
            params.width = rect.width
            params.height = rect.height
            if (params is ViewGroup.MarginLayoutParams) {
                params.leftMargin = rect.left
                params.topMargin = rect.top
                params.rightMargin = 0
                params.bottomMargin = 0
            }
            panel.layoutParams = params
        }

        private fun currentPanelRect(): SystemUiPanelRect {
            val params = panel.layoutParams
            val left = (params as? ViewGroup.MarginLayoutParams)?.leftMargin ?: panel.left
            val top = (params as? ViewGroup.MarginLayoutParams)?.topMargin ?: panel.top
            val width = dimension(panel.width, panel.measuredWidth, params?.width)
            val height = dimension(panel.height, panel.measuredHeight, params?.height)
            return SystemUiPanelRect(left, top, left + width, top + height)
        }

        private fun configureAppsPanel(appCount: Int, columnWidth: Int, columnHeight: Int) {
            val layout = SystemUiIndependentPanelPolicy.compactLayout(
                appCount = appCount,
                availableWidth = rootWidth(),
                availableHeight = rootHeight(),
                columnWidth = columnWidth,
                columnHeight = columnHeight,
                navigationWidth = 0,
                horizontalPadding = dp(PANEL_HORIZONTAL_PADDING_DP),
                verticalPadding = dp(PANEL_VERTICAL_PADDING_DP),
                columnSpacing = dp(COLUMN_SPACING_DP),
                headerHeight = 0,
                edgeMargin = dp(PANEL_EDGE_MARGIN_DP),
                emptyContentWidth = dp(EMPTY_CONTENT_WIDTH_DP),
            )
            configurePanelBounds(layout.width, layout.height)
            log(
                Log.DEBUG,
                TAG,
                "Compact panel apps=$appCount visible=${layout.visibleColumns} scroll=${layout.scrollable} " +
                        "size=${layout.width}x${layout.height}",
                null,
            )
        }

        /**
         * 计算设备选择页的目标面板矩形（基于折叠入口展开的设备页形态），只计算不落位。
         *
         * 进入设备选择页时用它作为展开动画的终点；[configureDevicePanel] 负责真正把
         * animationSpec 落位到该形态。
         */
        private fun devicePanelExpandedRect(deviceCount: Int): SystemUiPanelRect {
            val maximumWidth = rootWidth() - dp(PANEL_EDGE_MARGIN_DP) * 2
            val maximumHeight = rootHeight() - dp(PANEL_EDGE_MARGIN_DP) * 2
            val width = dp(DEVICE_PAGE_WIDTH_DP).coerceAtMost(maximumWidth)
            val naturalHeight = devicePanelNaturalHeight(deviceCount)
            return SystemUiIndependentPanelPolicy.animationSpec(
                folded = foldedRect,
                expandedWidth = width,
                expandedHeight = naturalHeight.coerceAtMost(maximumHeight),
                parentWidth = rootWidth(),
                parentHeight = rootHeight(),
                edgeMargin = dp(PANEL_EDGE_MARGIN_DP),
            ).expanded
        }

        /**
         * 设备页自然高度：实际行数 = 跟随系统固定 1 行 + 扫描设备（本机 + 外设）+
         * 可能的断开行。deviceCount 只是扫描设备数，必须补上 follow 行，否则面板偏矮，
         * 设备列表底部会被裁掉（表现为"只显示一行"）。
         *
         * 高度与 buildDevicePage 的布局精确对齐：header + 行数×行高 +
         * (行数+1)×行间距（每行 topMargin + rowsScroll topMargin）+ rowsContent
         * 顶部/底部 padding + 页面上下 padding。行少则面板矮（不空），行多封顶后
         * 由 ScrollView 滚动，底部自然露出半行提示可继续滚动。
         */
        private fun devicePanelNaturalHeight(deviceCount: Int): Int {
            val rowCount = deviceCount.coerceAtLeast(0) + DEVICE_PAGE_RESERVED_ROWS
            return dp(DEVICE_PAGE_HEADER_HEIGHT_DP) +
                    // header 下移的 topMargin。
                    dp(DEVICE_PAGE_ROW_SPACING_DP) +
                    rowCount * dp(DEVICE_ROW_HEIGHT_DP) +
                    (rowCount + 1) * dp(DEVICE_PAGE_ROW_SPACING_DP) +
                    // rowsContent 顶部 padding + 页面 top padding。
                    dp(DEVICE_PAGE_ROW_SPACING_DP) + dp(PANEL_VERTICAL_PADDING_DP) +
                    // 页面 bottom padding（rowsContent 底部已不额外留白）。
                    dp(DEVICE_PAGE_ROW_SPACING_DP)
        }

        /**
         * 计算应用概览页的目标面板矩形（基于折叠入口展开的 apps 页形态），只计算不落位。
         *
         * 从设备选择页返回概览时用它作为收缩动画的终点；[configureAppsPanel] 负责真正把
         * animationSpec 落位到该形态。
         */
        private fun appsPanelExpandedRect(
            appCount: Int,
            columnWidth: Int,
            columnHeight: Int
        ): SystemUiPanelRect {
            val layout = SystemUiIndependentPanelPolicy.compactLayout(
                appCount = appCount,
                availableWidth = rootWidth(),
                availableHeight = rootHeight(),
                columnWidth = columnWidth,
                columnHeight = columnHeight,
                navigationWidth = 0,
                horizontalPadding = dp(PANEL_HORIZONTAL_PADDING_DP),
                verticalPadding = dp(PANEL_VERTICAL_PADDING_DP),
                columnSpacing = dp(COLUMN_SPACING_DP),
                headerHeight = 0,
                edgeMargin = dp(PANEL_EDGE_MARGIN_DP),
                emptyContentWidth = dp(EMPTY_CONTENT_WIDTH_DP),
            )
            return SystemUiIndependentPanelPolicy.animationSpec(
                folded = foldedRect,
                expandedWidth = layout.width,
                expandedHeight = layout.height,
                parentWidth = rootWidth(),
                parentHeight = rootHeight(),
                edgeMargin = dp(PANEL_EDGE_MARGIN_DP),
            ).expanded
        }

        private fun configureDevicePanel(deviceCount: Int) {
            val maximumWidth = rootWidth() - dp(PANEL_EDGE_MARGIN_DP) * 2
            val maximumHeight = rootHeight() - dp(PANEL_EDGE_MARGIN_DP) * 2
            val width = dp(DEVICE_PAGE_WIDTH_DP).coerceAtMost(maximumWidth)
            val naturalHeight = devicePanelNaturalHeight(deviceCount)
            configurePanelBounds(width, naturalHeight.coerceAtMost(maximumHeight))
        }

        private fun configurePanelBounds(width: Int, height: Int) {
            animationSpec = SystemUiIndependentPanelPolicy.animationSpec(
                folded = foldedRect,
                expandedWidth = width,
                expandedHeight = height,
                parentWidth = rootWidth(),
                parentHeight = rootHeight(),
                edgeMargin = dp(PANEL_EDGE_MARGIN_DP),
            )
            applyPanelRect(
                SystemUiIndependentPanelPolicy.interpolateRect(
                    animationSpec,
                    animationFraction
                )
            )
        }

        private fun rootWidth(): Int = dimension(
            windowRoot.width,
            windowRoot.measuredWidth,
            targetContext.resources.displayMetrics.widthPixels,
        )

        private fun rootHeight(): Int = dimension(
            windowRoot.height,
            windowRoot.measuredHeight,
            targetContext.resources.displayMetrics.heightPixels,
        )

        private fun resolveColumnDimension(view: View, horizontal: Boolean, fallback: Int): Int {
            val layoutValue =
                if (horizontal) view.layoutParams?.width else view.layoutParams?.height
            if (layoutValue != null && layoutValue > 0) return layoutValue
            return try {
                view.measure(
                    View.MeasureSpec.makeMeasureSpec(rootWidth(), View.MeasureSpec.AT_MOST),
                    View.MeasureSpec.makeMeasureSpec(rootHeight(), View.MeasureSpec.AT_MOST),
                )
                val measured = if (horizontal) view.measuredWidth else view.measuredHeight
                measured.takeIf { it > 0 } ?: fallback
            } catch (error: RuntimeException) {
                log(Log.WARN, TAG, "Unable to pre-measure official VolumeColumn", error)
                fallback
            }
        }

        private fun applyOfficialColumnLayers(panelFraction: Float) {
            officialColumns.forEachIndexed { index, column ->
                val state =
                    SystemUiIndependentPanelPolicy.volumeColumnLayerState(index, panelFraction)
                column.view.alpha = state.alpha
                column.view.scaleX = state.scale
                column.view.scaleY = state.scale
                column.view.translationZ = -dp(COLUMN_TRANSLATION_Z_DP) * state.translationZFraction
            }
        }

        private fun startPolling() {
            val taskGeneration = generation.get()
            executor.scheduleWithFixedDelay(
                { pollSnapshot(taskGeneration) },
                0L,
                POLL_INTERVAL_MILLIS,
                TimeUnit.MILLISECONDS,
            )
        }

        private fun pollSnapshot(taskGeneration: Long) {
            if (!SystemUiGenerationGate.accepts(
                    closed.get(),
                    generation.get(),
                    taskGeneration
                )
            ) return
            if (trackingUids.isNotEmpty()) return
            try {
                val snapshot = panelBridge.snapshot()
                if (!SystemUiGenerationGate.accepts(
                        closed.get(),
                        generation.get(),
                        taskGeneration
                    )
                ) return
                if (snapshot.status == PanelPlaybackStatus.HOST_UNAVAILABLE) {
                    requestOverlayFallback(taskGeneration, "host unavailable")
                    return
                }
                if (snapshot.status == PanelPlaybackStatus.CONNECTING) return
                val fingerprint = SystemUiIndependentPanelPolicy.fingerprint(snapshot)
                if (fingerprint == lastFingerprint) return
                val loaded = loadSnapshot(snapshot)
                if (!SystemUiGenerationGate.accepts(
                        closed.get(),
                        generation.get(),
                        taskGeneration
                    )
                ) return
                lastFingerprint = fingerprint
                postToPanel(taskGeneration) { renderSnapshot(loaded) }
            } catch (throwable: Throwable) {
                log(Log.ERROR, TAG, "Unable to poll panel bridge", throwable)
                requestOverlayFallback(taskGeneration, "panel bridge failure")
            }
        }

        private fun loadSnapshot(snapshot: PanelPlaybackSnapshot): LoadedSnapshot {
            val packageManager = targetContext.packageManager
            val loadedByUid =
                snapshot.rows.associateBy(PanelPlaybackRow::uid).mapValues { (_, row) ->
                    val cacheKey = "${row.uid}:${row.packageName}"
                    val cached = appVisualCache[cacheKey]
                    val visual = if (cached != null) {
                        cached
                    } else {
                        val packageInfo = loadApplicationInfo(row)
                        val label = packageInfo?.let { info ->
                            runCatching {
                                info.loadLabel(packageManager).toString().takeIf(String::isNotBlank)
                            }
                                .onFailure { error ->
                                    log(
                                        Log.WARN,
                                        TAG,
                                        "Unable to load SystemUI package label package=${row.packageName}",
                                        error
                                    )
                                }
                                .getOrNull()
                        } ?: row.label ?: row.packageName
                        (label to loadApplicationIcon(
                            row,
                            packageInfo
                        )).also { appVisualCache[cacheKey] = it }
                    }
                    LoadedAppRow(
                        state = SystemUiBuiltinAppRowState(
                            row.packageName,
                            visual.first,
                            row.uid,
                            row.volumePercent
                        ),
                        protocolRow = row,
                        icon = visual.second,
                    )
                }
            val rows = SystemUiBuiltinPanelState.sorted(loadedByUid.values.map(LoadedAppRow::state))
                .map { state ->
                    checkNotNull(loadedByUid[state.uid]) { "Sorted panel row disappeared uid=${state.uid}" }
                }
            return LoadedSnapshot(snapshot, rows)
        }

        private fun loadApplicationInfo(row: PanelPlaybackRow): ApplicationInfo? {
            val userId = row.uid / PER_USER_RANGE
            val currentUserId = android.os.Process.myUid() / PER_USER_RANGE
            if (userId == currentUserId) {
                try {
                    return targetContext.packageManager.getApplicationInfo(row.packageName, 0)
                } catch (error: PackageManager.NameNotFoundException) {
                    log(
                        Log.WARN,
                        TAG,
                        "SystemUI PackageManager cannot see package=${row.packageName} user=$userId",
                        error,
                    )
                } catch (error: SecurityException) {
                    log(
                        Log.WARN,
                        TAG,
                        "SystemUI PackageManager denied package=${row.packageName} user=$userId",
                        error,
                    )
                }
            }
            return try {
                val launcherApps =
                    targetContext.getSystemService(Context.LAUNCHER_APPS_SERVICE) as? LauncherApps
                        ?: error("LauncherApps service unavailable")
                launcherApps.getApplicationInfo(
                    row.packageName,
                    0,
                    UserHandle.getUserHandleForUid(row.uid),
                )
            } catch (error: PackageManager.NameNotFoundException) {
                log(
                    Log.WARN,
                    TAG,
                    "Cross-user LauncherApps cannot see package=${row.packageName} user=$userId",
                    error,
                )
                null
            } catch (error: SecurityException) {
                log(
                    Log.WARN,
                    TAG,
                    "Cross-user LauncherApps denied package=${row.packageName} user=$userId",
                    error,
                )
                null
            } catch (error: RuntimeException) {
                log(
                    Log.WARN,
                    TAG,
                    "Cross-user LauncherApps lookup failed package=${row.packageName} user=$userId",
                    error,
                )
                null
            }
        }

        private fun loadApplicationIcon(row: PanelPlaybackRow, info: ApplicationInfo?): Drawable {
            val rowUserId = row.uid / PER_USER_RANGE
            val rowUser = UserHandle.getUserHandleForUid(row.uid)
            val currentUserId = android.os.Process.myUid() / PER_USER_RANGE
            val currentUserPackageIcon = if (rowUserId == currentUserId) {
                info?.let { applicationInfo ->
                    runCatching { applicationInfo.loadIcon(targetContext.packageManager) }
                        .onFailure { error ->
                            log(
                                Log.WARN,
                                TAG,
                                "Unable to load current-user PackageManager icon package=${row.packageName}",
                                error
                            )
                        }
                        .getOrNull()
                }
            } else {
                null
            }
            val launcherIcon = loadLauncherIcon(row, rowUser)
            val providerIcon = decodeProviderIcon(row)
            val candidates = buildList {
                currentUserPackageIcon?.let { add(SystemUiAppIconSource.SYSTEM_UI_PACKAGE to it) }
                launcherIcon?.let { add(SystemUiAppIconSource.LAUNCHER_APPS to it) }
                providerIcon?.let { add(SystemUiAppIconSource.PROVIDER_PAYLOAD to it) }
                add(SystemUiAppIconSource.DEFAULT_ICON to targetContext.packageManager.defaultActivityIcon)
            }
            candidates.forEach { (source, drawable) ->
                val rendered = runCatching { rasterizeDrawable(row.packageName, drawable) }
                    .onFailure { error ->
                        log(
                            Log.ERROR,
                            TAG,
                            "App icon candidate rejected package=${row.packageName} source=$source drawable=${drawable.javaClass.name}",
                            error,
                        )
                    }
                    .getOrNull()
                if (rendered != null) {
                    log(
                        Log.INFO,
                        TAG,
                        "Loaded app icon package=${row.packageName} source=$source drawable=${drawable.javaClass.name} " +
                                "rendered=${rendered.javaClass.name} size=${rendered.intrinsicWidth}x${rendered.intrinsicHeight}",
                        null,
                    )
                    return rendered
                }
            }
            error("No visible app icon candidate package=${row.packageName} uid=${row.uid}")
        }

        private fun loadLauncherIcon(row: PanelPlaybackRow, user: UserHandle): Drawable? = try {
            val launcherApps =
                targetContext.getSystemService(Context.LAUNCHER_APPS_SERVICE) as? LauncherApps
                    ?: error("LauncherApps service unavailable")
            val launcherInfo = launcherApps.getApplicationInfo(row.packageName, 0, user)
            launcherInfo.loadIcon(targetContext.packageManager)
        } catch (error: PackageManager.NameNotFoundException) {
            log(
                Log.WARN,
                TAG,
                "LauncherApps icon package not found package=${row.packageName} user=${row.uid / PER_USER_RANGE}",
                error
            )
            null
        } catch (error: SecurityException) {
            log(
                Log.WARN,
                TAG,
                "LauncherApps icon denied package=${row.packageName} user=${row.uid / PER_USER_RANGE}",
                error
            )
            null
        } catch (error: RuntimeException) {
            log(
                Log.WARN,
                TAG,
                "LauncherApps icon failed package=${row.packageName} user=${row.uid / PER_USER_RANGE}",
                error
            )
            null
        }

        private fun rasterizeDrawable(packageName: String, drawable: Drawable): Drawable {
            val size = dp(APP_ICON_RASTER_SIZE_DP)
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val oldBounds = drawable.bounds
            try {
                drawable.setBounds(0, 0, size, size)
                drawable.draw(canvas)
            } finally {
                drawable.bounds = oldBounds
            }
            check(bitmapHasVisiblePixel(bitmap)) {
                "Drawable rendered fully transparent package=$packageName type=${drawable.javaClass.name}"
            }
            return BitmapDrawable(targetContext.resources, bitmap).apply {
                setBounds(
                    0,
                    0,
                    size,
                    size
                )
            }
        }

        private fun bitmapHasVisiblePixel(bitmap: Bitmap): Boolean {
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            return SystemUiDrawablePixelVisibility.hasVisiblePixel(
                bitmap.width,
                bitmap.height,
                pixels
            )
        }

        private fun decodeProviderIcon(row: PanelPlaybackRow): Drawable? {
            val bytes = row.iconPng ?: run {
                log(
                    Log.WARN,
                    TAG,
                    "Provider supplied no app icon package=${row.packageName} uid=${row.uid}",
                    null
                )
                return null
            }
            return try {
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                if (bitmap == null) {
                    log(
                        Log.ERROR,
                        TAG,
                        "Provider app icon decode returned null package=${row.packageName}",
                        null
                    )
                    null
                } else if (!bitmapHasVisiblePixel(bitmap)) {
                    log(
                        Log.ERROR,
                        TAG,
                        "Provider app icon is fully transparent package=${row.packageName}",
                        null
                    )
                    null
                } else {
                    BitmapDrawable(targetContext.resources, bitmap).apply {
                        setBounds(0, 0, bitmap.width, bitmap.height)
                    }
                }
            } catch (error: RuntimeException) {
                log(
                    Log.ERROR,
                    TAG,
                    "Unable to decode provider app icon package=${row.packageName}",
                    error
                )
                null
            }
        }

        private fun renderSnapshot(loaded: LoadedSnapshot) {
            lastSnapshot = loaded.snapshot
            val selected = selectedPackage?.let { packageName ->
                loaded.apps.firstOrNull { it.state.packageName == packageName }
            }
            if (selectedPackage != null && selected == null) selectedPackage = null
            if (selected != null) {
                configureDevicePanel(loaded.snapshot.devices.size)
                replacePage(
                    buildDevicePage(selected, loaded.snapshot.devices),
                    forward = true,
                    animate = false
                )
            } else {
                val appsPage = buildAppsPage(loaded.apps)
                configureAppsPanel(loaded.apps.size, appsPage.columnWidth, appsPage.columnHeight)
                replacePage(appsPage.view, forward = false, animate = false)
            }
            startOpenAnimation()
        }

        private fun buildAppsPage(apps: List<LoadedAppRow>): AppsPageBuild {
            releaseOfficialColumns()
            val columns = LinearLayout(targetContext).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = null
                clipChildren = false
                clipToPadding = false
            }
            var resolvedColumnWidth = fallbackColumnWidth.coerceAtLeast(dp(MIN_COLUMN_WIDTH_DP))
            var resolvedColumnHeight = if (apps.isEmpty()) {
                dp(EMPTY_CONTENT_BODY_HEIGHT_DP)
            } else {
                fallbackColumnHeight.coerceAtLeast(dp(MIN_COLUMN_HEIGHT_DP))
            }
            if (apps.isEmpty()) {
                columns.addView(buildMessage(moduleContext.getString(R.string.panel_no_playing_apps)))
            } else {
                val streams =
                    SystemUiFakeStreamAllocator.allocate(apps.map { it.state.packageName })
                val builtColumns = apps.mapIndexed { index, app ->
                    buildAppColumn(app, streams.getValue(app.state.packageName))
                }
                resolvedColumnWidth = builtColumns.maxOf(AppColumnBuild::columnWidth)
                resolvedColumnHeight = builtColumns.maxOf(AppColumnBuild::columnHeight)
                builtColumns.forEachIndexed { index, built ->
                    columns.addView(
                        built.view,
                        LinearLayout.LayoutParams(resolvedColumnWidth, resolvedColumnHeight).apply {
                            if (index > 0) marginStart = dp(COLUMN_SPACING_DP)
                        },
                    )
                }
            }
            val scroll = HorizontalScrollView(targetContext).apply {
                isHorizontalScrollBarEnabled = false
                overScrollMode = View.OVER_SCROLL_NEVER
                background = null
                clipChildren = false
                clipToPadding = false
                isFillViewport = false
                addView(
                    columns,
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    ),
                )
            }
            val content = LinearLayout(targetContext).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = null
                clipChildren = false
                clipToPadding = false
                // 参考官方 MiuiVolumeDialogRes.getMarginTop（非 needShowDialog 时为
                // (屏幕高-面板高)/2 的对称居中）：上下 padding 对称，音量条在面板中垂直居中。
                setPadding(
                    dp(PANEL_HORIZONTAL_PADDING_DP),
                    dp(PANEL_VERTICAL_PADDING_DP),
                    dp(PANEL_HORIZONTAL_PADDING_DP),
                    dp(PANEL_VERTICAL_PADDING_DP),
                )
                addView(
                    scroll,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    ).apply {
                        // 列数较少时让整排音量条水平居中，而不是贴左。
                        gravity = Gravity.CENTER_HORIZONTAL
                    },
                )
            }
            return AppsPageBuild(content, resolvedColumnWidth, resolvedColumnHeight)
        }

        private fun buildNavigationRail(): View = LinearLayout(targetContext).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            background = null
            addView(
                buildIconButton(
                    resolvePluginDrawable(BACK_ICON_NAMES),
                    moduleContext.getString(R.string.panel_back),
                ) { close("back") },
                LinearLayout.LayoutParams(dp(MIN_ACTION_SIZE_DP), dp(MIN_ACTION_SIZE_DP)),
            )
        }

        private fun buildAppColumn(
            row: LoadedAppRow,
            fakeStream: Int,
        ): AppColumnBuild {
            val openDetails = {
                selectedPackage = row.state.packageName
                val devices = lastSnapshot?.devices.orEmpty()
                // 参考官方 VolumeExpandCollapsedAnimator.expand：进入设备选择使用尺寸/位置
                // 展开过渡，而不是横向翻页。先无动画替换内容，再从当前面板形态展开到设备页形态。
                val from = currentPanelRect()
                val to = devicePanelExpandedRect(devices.size)
                replacePage(buildDevicePage(row, devices), forward = true, animate = false)
                startPanelResizeAnimation(from, to) {
                    // 动画落定后把正式动画规格固定为设备页形态（供返回/关闭使用），并精确对齐目标矩形。
                    configureDevicePanel(devices.size)
                    animationFraction = 1f
                    applyPanelRect(
                        SystemUiIndependentPanelPolicy.interpolateRect(
                            animationSpec,
                            1f
                        )
                    )
                }
            }
            // 官方 VolumeColumn 需要传入 parent 完成 initColumn 挂载；wrapper 会重新把
            // official.view 直接 addView 进自己并强制拉伸到 officialWidth × sliderHeight，
            // 这里只给一个临时容器供官方初始化使用。
            val official = OfficialVolumeColumn.create(
                classLoader = pluginClassLoader,
                context = targetContext,
                parent = sliderContainer(targetContext),
                fakeStream = fakeStream,
                row = row,
                onTrackingChanged = { tracking ->
                    if (tracking) trackingUids += row.state.uid else trackingUids -= row.state.uid
                },
                onVolumeCommitted = ::submitVolume,
                onFailure = { throwable ->
                    log(
                        Log.ERROR,
                        TAG,
                        "Official VolumeColumn interaction failed uid=${row.state.uid}",
                        throwable
                    )
                },
            )
            official.prepareStandaloneColumn(row.state.packageName, log)
            officialColumns += official
            val pixelSizes =
                SystemUiColumnPixelSizes.fromDensity(targetContext.resources.displayMetrics.density)
            val officialWidth = resolveColumnDimension(
                official.view,
                horizontal = true,
                fallback = fallbackColumnWidth,
            ).coerceIn(dp(MIN_COLUMN_WIDTH_DP), dp(MAX_COLUMN_WIDTH_DP))
            val wrapperWidth = pixelSizes.wrapperWidth(officialWidth)
            val maximumSliderHeight = (
                    rootHeight() - dp(PANEL_EDGE_MARGIN_DP) * 2 - dp(PANEL_VERTICAL_PADDING_DP) * 2
                    ).coerceAtLeast(dp(MIN_COLUMN_HEIGHT_DP))
            val sliderHeight = resolveColumnDimension(
                official.view,
                horizontal = false,
                fallback = fallbackColumnHeight,
            ).coerceIn(
                dp(MIN_COLUMN_HEIGHT_DP),
                minOf(dp(MAX_COLUMN_HEIGHT_DP), maximumSliderHeight)
            )
            // 参考官方 VolumeColumn：icon/按钮作为音量条内部的内嵌覆盖层（官方布局
            // miui_volume_dialog_column 把 icon 放在 slider 内部，用 bottomMargin 定位），
            // 而不是在音量条外面再叠一排独立槽位。更多按钮在内部顶部，应用图标在内部底部。
            // 设备喇叭按钮已按用户要求移除，设备详情通过更多按钮或点击应用图标进入。
            val actions = LinearLayout(targetContext).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                background = null
                isClickable = false
                addView(
                    buildMoreButton(openDetails, official.slider),
                    LinearLayout.LayoutParams(pixelSizes.actionSize, pixelSizes.actionSize),
                )
            }
            // 应用图标：SoundMan 独立 ImageView，内容 = 应用图标原色（不叠加官方 tint），
            // 运行时对齐官方 slider 轨道的实际底部内部（用坐标计算，不依赖官方 icon 布局，
            // 避免官方 icon 的 tint/updateIcon 覆盖导致"蓝色/内容丢失/落在轨道下方"）。
            val appIconSize = dp(INNER_ICON_SIZE_DP)
            val appIconBottomInset = dp(ACTION_INSET_MARGIN_DP)
            val appIcon = ImageView(targetContext).apply {
                setImageDrawable(row.icon)
                imageTintList = null
                backgroundTintList = null
                background = null
                contentDescription = row.state.label
                isClickable = false
                isFocusable = false
                // 不设 OnTouchListener（返回 false 不消费）：触摸图标区域时事件会继续
                // 传给 wrapper 里的 sibling（official.view -> slider），音量条正常拖动，
                // 图标只是展示层，绝不阻隔触摸。
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                setPadding(dp(2), dp(2), dp(2), dp(2))
            }
            val wrapper = FrameLayout(targetContext).apply {
                background = null
                clipChildren = false
                clipToPadding = false
                // 官方 view 直接挂 wrapper，并强制拉伸到 officialWidth × sliderHeight，
                // 保证音量条（slider 轨道）正常显示。
                addView(
                    official.view,
                    FrameLayout.LayoutParams(officialWidth, sliderHeight, Gravity.CENTER),
                )
                // 内嵌顶部：更多 / 设备按钮（在音量条里面、靠上）。
                addView(
                    actions,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        Gravity.TOP or Gravity.CENTER_HORIZONTAL,
                    ).apply { topMargin = dp(ACTION_INSET_MARGIN_DP) },
                )
                // 应用图标：先按音量条底部兜底，布局完成后按 slider 轨道实际底部精确定位。
                addView(
                    appIcon,
                    FrameLayout.LayoutParams(
                        appIconSize,
                        appIconSize,
                        Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
                    ).apply { bottomMargin = appIconBottomInset },
                )
                // layout 后把图标底部对齐官方 slider 轨道的实际底部内部，水平居中，确保在音量条里面。
                addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
                    val sliderLoc = IntArray(2)
                    val wrapperLoc = IntArray(2)
                    official.slider.getLocationOnScreen(sliderLoc)
                    getLocationOnScreen(wrapperLoc)
                    val sliderTopInWrapper = sliderLoc[1] - wrapperLoc[1]
                    val sliderBottomInWrapper = sliderTopInWrapper + official.slider.height
                    if (sliderBottomInWrapper <= 0) return@addOnLayoutChangeListener
                    val wrapperWidth = v.width
                    appIcon.layoutParams = FrameLayout.LayoutParams(
                        appIconSize,
                        appIconSize,
                        Gravity.LEFT or Gravity.TOP,
                    ).apply {
                        leftMargin = (wrapperWidth - appIconSize) / 2
                        topMargin = sliderBottomInWrapper - appIconSize - appIconBottomInset
                    }
                    appIcon.requestLayout()
                }
            }
            val regions = SystemUiIndependentPanelPolicy.columnRegions(
                columnWidth = wrapperWidth,
                sliderWidth = officialWidth,
                sliderHeight = sliderHeight,
                sizes = pixelSizes,
            )
            check(!regions.hasOverlap()) { "App column regions overlap package=${row.state.packageName}: $regions" }
            // 列始终保持完整状态（alpha=1、scale=1、无分层位移）：打开/展开动画改为
            // 整个面板内容（音量条 + 更多按钮 + 应用图标）作为整体由 panel.alpha 与面板
            // morph 驱动，不再对音量列做分层错峰，保证三者整体连贯。
            applyOfficialColumnLayers(1f)
            return AppColumnBuild(
                wrapper,
                wrapperWidth,
                sliderHeight,
            )
        }

        private fun sliderContainer(context: Context): ViewGroup = FrameLayout(context).apply {
            background = null
            clipChildren = false
            clipToPadding = false
        }

        private fun buildDevicePage(app: LoadedAppRow, devices: List<AudioOutputDevice>): View {
            releaseOfficialColumns()
            // 设备选择页不再并排展示一个音量列（否则会像官方音量面板折叠时左侧残留半截音量条），
            // 参考官方音量面板的"仅内容"展开方式：顶部 header + 全宽设备列表，音量调节回到概览页。
            val backToOverview = {
                selectedPackage = null
                val snapshot = lastSnapshot
                if (snapshot == null) {
                    log(Log.ERROR, TAG, "Cannot return to overview without a snapshot", null)
                } else {
                    val apps = loadSnapshot(snapshot).apps
                    val appsPage = buildAppsPage(apps)
                    // 返回概览与进入方向相反：从设备页形态收缩回 apps 页形态。
                    val from = currentPanelRect()
                    val to = appsPanelExpandedRect(
                        apps.size,
                        appsPage.columnWidth,
                        appsPage.columnHeight
                    )
                    replacePage(appsPage.view, forward = false, animate = false)
                    startPanelResizeAnimation(from, to) {
                        // 动画落定后把正式动画规格恢复为 apps 页形态，并精确对齐目标矩形。
                        configureAppsPanel(apps.size, appsPage.columnWidth, appsPage.columnHeight)
                        animationFraction = 1f
                        applyPanelRect(
                            SystemUiIndependentPanelPolicy.interpolateRect(
                                animationSpec,
                                1f
                            )
                        )
                    }
                }
            }
            val rowsContent = LinearLayout(targetContext).apply {
                orientation = LinearLayout.VERTICAL
                // 底部不额外留白（最后一行卡片底 → 页面 bottom padding），让最后一行
                // 卡片到面板底部的距离与卡片之间间距一致，不再"边框下方距离割裂"。
                setPadding(
                    dp(DEVICE_PAGE_HORIZONTAL_PADDING_DP),
                    dp(DEVICE_PAGE_ROW_SPACING_DP),
                    dp(DEVICE_PAGE_HORIZONTAL_PADDING_DP),
                    0
                )
            }
            deviceRows.build(
                scan = AudioDeviceScan(devices, null),
                rule = app.protocolRow.asRule(),
                followSystemName = moduleContext.getString(R.string.output_follow_system),
                builtinName = moduleContext.getString(R.string.output_device_builtin),
            ).forEach { deviceRow ->
                rowsContent.addView(
                    buildDeviceRow(app, deviceRow),
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        dp(DEVICE_ROW_HEIGHT_DP)
                    ).apply { topMargin = dp(DEVICE_PAGE_ROW_SPACING_DP) },
                )
            }
            // 列表容器：内容超出时正常滚动；内容不足时也能上下拖动并松手回弹，
            // 保留"拖动列表"的手感（用户要求：哪怕页面没超出也能拖动）。
            // 用标准 onInterceptTouchEvent 模式：只有纵向移动超过 touch slop 才接管拖动，
            // 无移动的按下/松开仍交给设备行处理点击（切换设备），两者互不干扰。
            val rowsScroll = object : ScrollView(targetContext) {
                private var downY = 0f
                private var contentDragging = false
                private val touchSlop = ViewConfiguration.get(targetContext).scaledTouchSlop

                private fun contentOverflow(): Boolean {
                    val contentView = getChildAt(0)
                    return contentView != null && contentView.height > height
                }

                override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
                    if (contentOverflow()) return super.onInterceptTouchEvent(event)
                    // 内容未超出：只有纵向移动超过 touch slop 才拦截，接管拖动。
                    return when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            downY = event.y
                            contentDragging = false
                            false
                        }

                        MotionEvent.ACTION_MOVE ->
                            if (!contentDragging && kotlin.math.abs(event.y - downY) > touchSlop) {
                                contentDragging = true
                                true
                            } else {
                                false
                            }

                        else -> false
                    }
                }

                override fun onTouchEvent(event: MotionEvent): Boolean {
                    if (contentOverflow() || !contentDragging) return super.onTouchEvent(event)
                    // 内容未超出且已接管拖动：拖动内容 view，松手回弹。
                    return when (event.actionMasked) {
                        MotionEvent.ACTION_MOVE -> {
                            val contentView = getChildAt(0)
                            // 半阻力，避免拖太远。
                            contentView?.translationY = (event.y - downY) * 0.5f
                            true
                        }

                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                            val contentView = getChildAt(0)
                            contentView?.animate()
                                ?.translationY(0f)
                                ?.setDuration(220L)
                                ?.setInterpolator(android.view.animation.DecelerateInterpolator())
                                ?.start()
                            contentDragging = false
                            true
                        }

                        else -> true
                    }
                }
            }.apply {
                isVerticalScrollBarEnabled = false
                overScrollMode = View.OVER_SCROLL_NEVER
                addView(rowsContent)
            }
            return LinearLayout(targetContext).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                background = null
                setPadding(
                    dp(DEVICE_PAGE_HORIZONTAL_PADDING_DP),
                    dp(PANEL_VERTICAL_PADDING_DP),
                    dp(DEVICE_PAGE_HORIZONTAL_PADDING_DP),
                    dp(DEVICE_PAGE_ROW_SPACING_DP)
                )
                // 顶部 header：返回按钮 + 应用图标 + 应用名，左右对称，标题视觉居中。
                addView(
                    LinearLayout(targetContext).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        background = null
                        // 左侧返回按钮：36dp 内图标适当放大，避免 padding 过大导致箭头过小。
                        // marginStart 把返回按钮左缘推到与设备列表行（圆角条）左缘对齐，
                        // 而不是贴着页面 padding 偏左一截。
                        addView(
                            buildIconButton(
                                resolvePluginDrawable(BACK_ICON_NAMES),
                                moduleContext.getString(R.string.panel_back),
                                backToOverview,
                            ).apply {
                                setPadding(dp(6), dp(6), dp(6), dp(6))
                            },
                            LinearLayout.LayoutParams(
                                dp(HEADER_ACTION_SIZE_DP),
                                dp(HEADER_ACTION_SIZE_DP)
                            ).apply {
                                marginStart = dp(DEVICE_PAGE_HORIZONTAL_PADDING_DP)
                            },
                        )
                        // 中间标题区：图标 + 名称，weight 撑满并居中，右侧用等宽占位保证对称。
                        addView(
                            LinearLayout(targetContext).apply {
                                orientation = LinearLayout.HORIZONTAL
                                gravity = Gravity.CENTER
                                background = null
                                addView(
                                    ImageView(targetContext).apply {
                                        setImageDrawable(app.icon)
                                        imageTintList = null
                                        background = null
                                        scaleType = ImageView.ScaleType.CENTER_INSIDE
                                        setPadding(dp(2), dp(2), dp(2), dp(2))
                                    },
                                    LinearLayout.LayoutParams(
                                        dp(HEADER_ICON_SIZE_DP),
                                        dp(HEADER_ICON_SIZE_DP)
                                    )
                                )
                                addView(
                                    TextView(targetContext).apply {
                                        text = app.state.label
                                        setTextColor(Color.WHITE)
                                        textSize = 16f
                                        maxLines = 1
                                        gravity = Gravity.CENTER_VERTICAL
                                    },
                                    LinearLayout.LayoutParams(
                                        ViewGroup.LayoutParams.WRAP_CONTENT,
                                        ViewGroup.LayoutParams.MATCH_PARENT
                                    ).apply {
                                        marginStart = dp(8)
                                    })
                            },
                            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f),
                        )
                        // 右侧等宽占位，让标题真正居中；与左侧返回按钮的 marginStart 对称。
                        addView(
                            View(targetContext),
                            LinearLayout.LayoutParams(
                                dp(HEADER_ACTION_SIZE_DP),
                                dp(HEADER_ACTION_SIZE_DP)
                            ).apply {
                                marginEnd = dp(DEVICE_PAGE_HORIZONTAL_PADDING_DP)
                            },
                        )
                    },
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        dp(HEADER_ACTION_SIZE_DP)
                    ).apply {
                        // 标题栏整体下移一点，避免贴顶显得拥挤。
                        topMargin = dp(DEVICE_PAGE_ROW_SPACING_DP)
                    },
                )
                addView(
                    rowsScroll, LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        0,
                        1f,
                    ).apply { topMargin = dp(DEVICE_PAGE_ROW_SPACING_DP) })
            }
        }

        private fun buildDeviceRow(app: LoadedAppRow, row: DevicePageRow): View {
            val selectedColor = Color.argb(235, 255, 255, 255)
            val idleColor = Color.argb(48, 255, 255, 255)
            val textColor = when {
                !row.enabled -> Color.argb(110, 255, 255, 255)
                row.selected -> Color.rgb(17, 17, 17)
                else -> Color.WHITE
            }
            return LinearLayout(targetContext).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                isEnabled = row.enabled
                alpha = if (row.enabled) 1f else 0.62f
                background = roundedBackground(
                    if (row.selected) selectedColor else idleColor,
                    dp(22).toFloat()
                )
                // 行内左右留白用独立常量（16dp），保证图标与圆角边框之间有足够呼吸距离；
                // 页面横向边框（DEVICE_PAGE_HORIZONTAL_PADDING_DP）保持较窄。
                setPadding(
                    dp(DEVICE_ROW_HORIZONTAL_PADDING_DP),
                    0,
                    dp(DEVICE_ROW_HORIZONTAL_PADDING_DP),
                    0
                )
                addView(ImageView(targetContext).apply {
                    setImageDrawable(resolveDeviceIcon(row))
                    imageTintList = ColorStateList.valueOf(
                        if (row.selected) Color.rgb(52, 120, 246) else textColor,
                    )
                    scaleType = ImageView.ScaleType.CENTER_INSIDE
                }, LinearLayout.LayoutParams(dp(28), dp(28)))
                addView(
                    TextView(targetContext).apply {
                        text = row.name
                        setTextColor(textColor)
                        textSize = 16f
                        maxLines = 1
                        gravity = Gravity.CENTER_VERTICAL
                    },
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
                        .apply { marginStart = dp(12) })
                setOnClickListener {
                    if (!row.enabled) return@setOnClickListener
                    val target =
                        checkNotNull(row.clickTarget) { "Disconnected device row must not be clickable" }
                    submitRoute(app, target)
                }
            }
        }

        private fun replacePage(next: View, forward: Boolean, animate: Boolean) {
            val previous = currentPage
            if (!animate || previous == null) {
                previous?.let(pageHost::removeView)
                pageHost.removeAllViews()
                pageHost.addView(
                    next,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                )
                currentPage = next
                return
            }
            val distance = (pageHost.width.takeIf { it > 0 } ?: panel.width).toFloat()
                .coerceAtLeast(dp(120).toFloat())
            val direction = if (forward) 1f else -1f
            next.translationX = distance * direction
            next.alpha = 0f
            pageHost.addView(
                next,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
            previous.animate().cancel()
            next.animate().cancel()
            previous.animate()
                .translationX(-distance * direction * 0.35f)
                .alpha(0f)
                .setDuration(PAGE_ANIMATION_DURATION_MILLIS)
                .setInterpolator(EXPAND_INTERPOLATOR)
                .setListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        previous.animate().setListener(null)
                        pageHost.removeView(previous)
                    }
                })
                .start()
            next.animate()
                .translationX(0f)
                .alpha(1f)
                .setDuration(PAGE_ANIMATION_DURATION_MILLIS)
                .setInterpolator(EXPAND_INTERPOLATOR)
                .start()
            currentPage = next
        }

        private fun submitVolume(state: SystemUiBuiltinAppRowState, percent: Int) {
            val taskGeneration = generation.get()
            executeBridge(taskGeneration, "set volume uid=${state.uid}") {
                panelBridge.setVolume(state.packageName, state.uid, percent)
                lastFingerprint = null
            }
        }

        private fun submitRoute(app: LoadedAppRow, target: OutputTarget) {
            val taskGeneration = generation.get()
            executeBridge(taskGeneration, "set route uid=${app.state.uid}") {
                panelBridge.setRoute(app.state.packageName, app.state.uid, target)
                lastFingerprint = null
                val snapshot = panelBridge.snapshot()
                val loaded = loadSnapshot(snapshot)
                postToPanel(taskGeneration) { renderSnapshot(loaded) }
            }
        }

        private fun executeBridge(taskGeneration: Long, operation: String, action: () -> Unit) {
            try {
                executor.execute {
                    try {
                        if (!SystemUiGenerationGate.accepts(
                                closed.get(),
                                generation.get(),
                                taskGeneration
                            )
                        ) return@execute
                        action()
                    } catch (throwable: Throwable) {
                        log(Log.ERROR, TAG, "Unable to $operation", throwable)
                        requestOverlayFallback(taskGeneration, "$operation failure")
                    }
                }
            } catch (throwable: Throwable) {
                if (!closed.get()) {
                    log(Log.ERROR, TAG, "Panel executor rejected $operation", throwable)
                    requestOverlayFallback(taskGeneration, "$operation rejected")
                }
            }
        }

        private fun requestOverlayFallback(taskGeneration: Long, reason: String) {
            postToPanel(taskGeneration) {
                if (!SystemUiFallbackPolicy.shouldRequest(
                        closed = closed.get(),
                        currentGeneration = generation.get(),
                        resultGeneration = taskGeneration,
                        alreadyRequested = fallbackRequested.get(),
                    )
                ) return@postToPanel
                if (!fallbackRequested.compareAndSet(false, true)) return@postToPanel
                close(reason) {
                    try {
                        openOverlay()
                    } catch (throwable: Throwable) {
                        log(
                            Log.ERROR,
                            TAG,
                            "Panel overlay fallback failed reason=$reason",
                            throwable
                        )
                    }
                }
            }
        }

        private fun postToPanel(taskGeneration: Long, action: () -> Unit) {
            if (!SystemUiGenerationGate.accepts(
                    closed.get(),
                    generation.get(),
                    taskGeneration
                )
            ) return
            val target = if (::panel.isInitialized && panel.isAttachedToWindow) panel else dialog
            if (!target.isAttachedToWindow) {
                log(
                    Log.WARN,
                    TAG,
                    "Dropped panel UI result because volume window is detached",
                    null
                )
                return
            }
            val accepted = target.post {
                if (!SystemUiGenerationGate.accepts(
                        closed.get(),
                        generation.get(),
                        taskGeneration
                    )
                ) return@post
                try {
                    action()
                } catch (throwable: Throwable) {
                    log(Log.ERROR, TAG, "Panel UI action failed", throwable)
                    requestOverlayFallback(taskGeneration, "render failure")
                }
            }
            if (!accepted) log(Log.ERROR, TAG, "Volume View rejected panel UI result", null)
        }

        fun close(reason: String, afterClosed: (() -> Unit)? = null) {
            if (!closed.compareAndSet(false, true)) return
            generation.incrementAndGet()
            executor.shutdownNow()
            if (!::panel.isInitialized || panel.parent == null || !panel.isAttachedToWindow || !openAnimationStarted) {
                finishClose(reason, afterClosed)
                return
            }
            // 参考官方 MiuiVolumeDialogMotion.dismissVolumePanelAnimation -> VolumeShowHideAnimator.hide：
            // 关闭是把面板直接滑出屏幕（X 平移到 dismissX），而不是 morph 收缩回折叠态。
            startHideSlideAwayAnimation {
                finishClose(reason, afterClosed)
            }
        }

        /**
         * 参考官方 MiuiVolumeDialogMotion.dismissVolumePanelAnimation ->
         * VolumeShowHideAnimator.expanded(false)：把整个面板滑出屏幕。
         *
         * 官方 hide：mVolumeView（面板）X 滑到 dismissX 且保持不透明（内容清晰可见），
         * 仅 mVolumeContainer scale 收窄到 0.8、独立 shadow view 跟随 X 移动并淡出；
         * 面板位于屏幕下方区域时轨迹视觉上呈"向右下滑走"。这里在 X 滑出的同时叠加一段
         * 向下位移（dismissY）让轨迹明确斜向右下；SoundMan 无独立 shadow view（面板
         * 背景材质即"阴影"），背景随面板整体滑出，因此 panel 保持不透明（不再整体
         * 淡出，避免内容随 alpha 一起消失显得"阴影/内容透明度计算有毛病"）。
         * 内容（音量条/按钮/图标）作为整体随面板同步移动，结束后移除面板。
         */
        private fun startHideSlideAwayAnimation(onEnd: () -> Unit) {
            morphAnimator?.cancel()
            resizeAnimator?.cancel()
            slideAwayAnimator?.cancel()
            val panelRect = currentPanelRect()
            val anchoredRight = panelRect.left + panelRect.width / 2 >= rootWidth() / 2
            val dismissX = if (anchoredRight) {
                rootWidth() + panelRect.width
            } else {
                -(panelRect.width * 2)
            }
            val dismissY = panelRect.height / 3
            slideAwayAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = HIDE_SLIDE_ANIMATION_DURATION_MILLIS
                interpolator = HIDE_INTERPOLATOR
                addUpdateListener { animator ->
                    val fraction = animator.animatedValue as Float
                    panel.translationX = dismissX * fraction
                    panel.translationY = dismissY * fraction
                    panel.scaleX = 1f - 0.2f * fraction
                    panel.scaleY = 1f - 0.2f * fraction
                }
                addListener(object : AnimatorListenerAdapter() {
                    private var cancelled = false

                    override fun onAnimationCancel(animation: Animator) {
                        cancelled = true
                    }

                    override fun onAnimationEnd(animation: Animator) {
                        // 重置为入场初始状态，避免残留 transform 影响后续复用。
                        panel.translationX = 0f
                        panel.translationY = 0f
                        panel.scaleX = 1f
                        panel.scaleY = 1f
                        panel.alpha = 1f
                        if (!cancelled) onEnd()
                    }
                })
                start()
            }
        }

        /**
         * 参考官方 VolumeExpandCollapsedAnimator.expand：以尺寸/位置状态过渡的方式
         * 把面板从 [from] 展开到 [to]（进入设备选择页），不改变 panel 透明度。
         */
        private fun startPanelResizeAnimation(
            from: SystemUiPanelRect,
            to: SystemUiPanelRect,
            onEnd: (() -> Unit)? = null,
        ) {
            morphAnimator?.cancel()
            resizeAnimator?.cancel()
            resizeAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = EXPAND_ANIMATION_DURATION_MILLIS
                interpolator = EXPAND_INTERPOLATOR
                addUpdateListener { animator ->
                    val fraction = animator.animatedValue as Float
                    animationFraction = fraction
                    applyPanelRect(
                        SystemUiIndependentPanelPolicy.interpolateRect(
                            SystemUiIndependentPanelAnimationSpec(from, to),
                            fraction,
                        ),
                    )
                    // 页面内容随面板 resize 同步整体过渡（参考官方 VolumeExpandCollapsedAnimator
                    // 的 SIZE/POSITION/COLOR 整体插值）：新页面整体淡入 + 轻微放大，与面板
                    // 尺寸/位置变化同步，不做列分层，保证音量条/按钮/图标整体连贯。
                    currentPage?.let { page ->
                        page.alpha = fraction
                        page.scaleX = 0.96f + 0.04f * fraction
                        page.scaleY = 0.96f + 0.04f * fraction
                    }
                }
                addListener(object : AnimatorListenerAdapter() {
                    private var cancelled = false

                    override fun onAnimationCancel(animation: Animator) {
                        cancelled = true
                    }

                    override fun onAnimationEnd(animation: Animator) {
                        if (!cancelled) onEnd?.invoke()
                    }
                })
                start()
            }
        }

        fun closeImmediately(reason: String) {
            if (closed.compareAndSet(false, true)) generation.incrementAndGet()
            executor.shutdownNow()
            releaseOfficialColumns()
            finishClose(reason, null)
        }

        private fun releaseOfficialColumns() {
            val copy = officialColumns.toList()
            officialColumns.clear()
            copy.forEach { column ->
                try {
                    column.release()
                } catch (throwable: Throwable) {
                    log(Log.ERROR, TAG, "Unable to release official VolumeColumn", throwable)
                }
            }
        }

        private fun finishClose(reason: String, afterClosed: (() -> Unit)?) {
            val closeState = SystemUiIndependentPanelPolicy.closeState(reason)
            check(closeState.terminal) { "Independent panel close must be terminal" }
            releaseOfficialColumns()
            try {
                if (::panel.isInitialized) {
                    morphAnimator?.cancel()
                    if (::expandedMaterial.isInitialized) expandedMaterial.clear(panel)
                    panel.removeAllViews()
                    panel.background = null
                }
                if (::host.isInitialized) {
                    (host.parent as? ViewGroup)?.removeView(host)
                    host.setOnTouchListener(null)
                    host.removeAllViews()
                    host.background = null
                }
            } catch (throwable: Throwable) {
                log(Log.ERROR, TAG, "Unable to remove independent full-window host", throwable)
            }
            try {
                touchInsets.restore()
            } catch (throwable: Throwable) {
                log(
                    Log.ERROR,
                    TAG,
                    "Unable to restore official touchable-region listener",
                    throwable
                )
            }
            try {
                dialog.importantForAccessibility = originalImportantForAccessibility
                if (closeState.dismissOfficialSession) {
                    // 复用官方完整关闭路线（controller.dismissH -> MiuiVolumeDialogMotion
                    // dismissVolumePanel -> VolumeShowHideAnimator.hide）：官方 dismiss
                    // 动画会把 dialog 的 X 平移到屏幕外（dismissX），这就是官方的"隐藏"。
                    //
                    // 时序处理避免侧边栏阴影闪现：
                    // 1) 先把 dialog 设为 VISIBLE + alpha 0 —— 官方 dismissH 需要
                    //    isShown()=true 才执行（dismissVolumePanel 前置检查），alpha 0
                    //    保证 dismiss 动画期间侧边栏不可见；
                    // 2) dismissH 同步触发官方动画（异步播放）；
                    // 3) 等官方 dismiss 动画完成（dialog X 已到屏幕外）后再恢复 dialog
                    //    自身 alpha/visibility 到接管前状态 —— 此时 dialog 虽 VISIBLE
                    //    但在屏幕外不可见，不会闪现；下次官方 showH 时动画把 X 滑回
                    //    屏幕内，音量条恢复可用。
                    dialog.visibility = View.VISIBLE
                    dialog.alpha = 0f
                    check(OfficialVolumeDismissBridge.dismiss(dialog, hookDismiss, log)) {
                        "All official volume dismiss entries failed"
                    }
                    // 官方 dismiss 动画时长约 467ms（folme spring，见
                    // VolumeShowHideAnimator 的 trackVolumePanelAnimEnd 常量），
                    // 延迟略长于动画结束再恢复，确保 dialog X 已停在屏幕外。
                    dialog.postDelayed({
                        dialog.alpha = originalAlpha
                        dialog.visibility = originalVisibility
                        dialog.importantForAccessibility = originalImportantForAccessibility
                    }, OFFICIAL_DISMISS_ANIMATION_MILLIS + 120L)
                } else {
                    dialog.alpha = originalAlpha
                    dialog.visibility = originalVisibility
                }
            } catch (throwable: Throwable) {
                dialog.alpha = originalAlpha
                dialog.visibility = originalVisibility
                dialog.importantForAccessibility = originalImportantForAccessibility
                log(
                    Log.ERROR,
                    TAG,
                    "Unable to complete official volume dismiss lifecycle",
                    throwable
                )
            }
            try {
                onClosed(this)
            } catch (throwable: Throwable) {
                log(Log.ERROR, TAG, "Independent panel onClosed callback failed", throwable)
            }
            try {
                afterClosed?.invoke()
            } catch (throwable: Throwable) {
                log(Log.ERROR, TAG, "Independent panel completion callback failed", throwable)
            }
            log(Log.INFO, TAG, "Closed independent app-volume panel reason=$reason", null)
        }

        private fun buildMoreButton(action: () -> Unit, slider: SeekBar): ImageView =
            ImageView(targetContext).apply {
                // 官方展开/收起箭头名存在多个 ROM 版本差异，按候选顺序逐个尝试；
                // 全部缺失时才回退默认图标，绝不让按钮构建失败拖垮整个面板。
                setImageDrawable(resolvePluginDrawable(MORE_ICON_NAMES))
                // 取色完全参考官方（jadx 逆向）：
                // - MiuiVolumeDialogView.updateExpandButtonTint：bionics 高级材质下
                //   setImageTintList(null)（图标用 drawable 原色），否则用官方颜色资源；
                // - MiuiVolumeDialogView.initExpandButtonBlend：高级材质下用
                //   Util.setMiViewBlurAndBlendColor(button, 3, getExpandedIconBlandColor())
                //   让图标与玻璃背景融合（miuix ColorBlendToken 渲染），否则关 blur。
                applyOfficialExpandButtonStyle(this)
                contentDescription = moduleContext.getString(R.string.panel_more_devices)
                isClickable = true
                isFocusable = true
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                setPadding(
                    dp(INNER_ICON_PADDING_DP),
                    dp(INNER_ICON_PADDING_DP),
                    dp(INNER_ICON_PADDING_DP),
                    dp(INNER_ICON_PADDING_DP)
                )
                // 参考官方 MiuiVolumeSeekBar.doClick：只有按下后快速松开（<200ms 且移动 < 20px）
                // 才视为点击进入设备选择；一旦判定为拖动，把事件转交给 slider 继续调音量，
                // 更多按钮绝不能遮挡音量条的拖动触摸。
                var downX = 0f
                var downY = 0f
                var downTime = 0L
                var dragging = false
                val touchSlopPx = dp(MORE_BUTTON_TOUCH_SLOP_DP)
                setOnTouchListener { view, event ->
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            downX = event.x
                            downY = event.y
                            downTime = android.os.SystemClock.uptimeMillis()
                            dragging = false
                            true
                        }

                        MotionEvent.ACTION_MOVE -> {
                            val dx = event.x - downX
                            val dy = event.y - downY
                            val moved =
                                kotlin.math.abs(dx) > touchSlopPx || kotlin.math.abs(dy) > touchSlopPx
                            if (moved) {
                                dragging = true
                                // 把本次移动转交给 slider 作为拖动起点（按下位置已在按钮上）。
                                forwardToSlider(slider, view, event, down = true)
                            } else if (dragging) {
                                forwardToSlider(slider, view, event, down = false)
                            }
                            true
                        }

                        MotionEvent.ACTION_UP -> {
                            val elapsed = android.os.SystemClock.uptimeMillis() - downTime
                            if (dragging) {
                                forwardToSlider(slider, view, event, down = false)
                            } else if (elapsed < MORE_BUTTON_CLICK_TIMEOUT_MILLIS) {
                                action()
                            }
                            dragging = false
                            true
                        }

                        MotionEvent.ACTION_CANCEL -> {
                            dragging = false
                            true
                        }

                        else -> true
                    }
                }
            }

        /**
         * 把更多按钮上的触摸事件转换到 slider 的坐标系后转发，让音量条拖动不被按钮遮挡。
         *
         * 官方 MiuiVolumeSeekBar 在 doClick 判定为拖动时，把 MOVE 转成 DOWN 交给自己的
         * onTouchEvent；这里按钮与 slider 是两个 view，因此需要做坐标平移后 dispatch。
         */
        private fun forwardToSlider(
            slider: SeekBar,
            button: View,
            event: MotionEvent,
            down: Boolean
        ) {
            val buttonLocation = IntArray(2)
            val sliderLocation = IntArray(2)
            button.getLocationOnScreen(buttonLocation)
            slider.getLocationOnScreen(sliderLocation)
            val localX = buttonLocation[0] - sliderLocation[0] + event.x
            val localY = buttonLocation[1] - sliderLocation[1] + event.y
            val forwarded = MotionEvent.obtain(event)
            try {
                if (down) {
                    forwarded.setAction(MotionEvent.ACTION_DOWN)
                }
                forwarded.setLocation(localX, localY)
                slider.dispatchTouchEvent(forwarded)
            } finally {
                forwarded.recycle()
            }
        }

        private fun buildIconButton(
            icon: Drawable,
            description: String,
            action: () -> Unit
        ): ImageView =
            ImageView(targetContext).apply {
                setImageDrawable(icon)
                imageTintList = ColorStateList.valueOf(Color.WHITE)
                contentDescription = description
                isClickable = true
                isFocusable = true
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                setPadding(dp(10), dp(10), dp(10), dp(10))
                setOnClickListener { action() }
            }

        private fun buildMessage(message: String): TextView = TextView(targetContext).apply {
            text = message
            setTextColor(Color.WHITE)
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(dp(24), 0, dp(24), 0)
            layoutParams = LinearLayout.LayoutParams(
                dp(EMPTY_CONTENT_WIDTH_DP),
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        private fun resolveDeviceIcon(row: DevicePageRow): Drawable {
            // 参考官方 miplay 设备图标体系：
            // 跟随本机 -> ic_miplay_pc；本机 -> ic_miplay_phone；蓝牙耳机 -> ic_miplay_headset；
            // 蓝牙音响 -> ic_miplay_speaker；其他蓝牙 -> ic_miplay_default；
            // 有线耳机/其他设备 -> ic_wired_headset_microphone。
            if (row.kind == DevicePageRowKind.FOLLOW_SYSTEM) {
                return resolvePluginDrawable(
                    arrayOf(
                        "ic_miplay_pc",
                        "ic_miplay_default",
                        "ic_miui_volume_speaker"
                    )
                )
            }
            val names = when (row.type) {
                OutputDeviceType.BUILT_IN ->
                    arrayOf("ic_miplay_phone", "ic_miplay_default", "ic_miui_volume_speaker")

                OutputDeviceType.WIRED_HEADSET ->
                    arrayOf(
                        "ic_wired_headset_microphone",
                        "ic_miplay_headset",
                        "ic_miui_volume_headset"
                    )

                OutputDeviceType.BLUETOOTH -> bluetoothDeviceIconNames(row)
                OutputDeviceType.USB ->
                    arrayOf(
                        "ic_wired_headset_microphone",
                        "ic_miplay_default",
                        "ic_miui_volume_usb"
                    )

                null, OutputDeviceType.OTHER ->
                    arrayOf(
                        "ic_wired_headset_microphone",
                        "ic_miplay_default",
                        "ic_miui_volume_media"
                    )
            }
            return resolvePluginDrawable(names)
        }

        /**
         * 蓝牙设备细分图标：按设备名关键词区分耳机 / 音响 / 其他蓝牙设备。
         *
         * SoundMan 面板侧拿不到隐藏 DEVICE_OUT_* 常量做 internalType 判定，这里用
         * productName 关键词兜底（与系统对 BLE_HEADSET / BLE_SPEAKER 的直观命名一致）。
         */
        private fun bluetoothDeviceIconNames(row: DevicePageRow): Array<String> {
            val productName =
                ((row.clickTarget as? OutputTarget.Device)?.productName ?: row.name).lowercase()
            val speakerKeywords = arrayOf("speaker", "soundbar", "音箱", "音响", "sound", "bar")
            val headsetKeywords =
                arrayOf("headset", "headphone", "earphone", "earbud", "耳机", "headphones")
            return when {
                speakerKeywords.any { productName.contains(it) } && headsetKeywords.none {
                    productName.contains(
                        it
                    )
                } ->
                    arrayOf("ic_miplay_speaker", "ic_miplay_headset", "ic_miplay_default")

                headsetKeywords.any { productName.contains(it) } ->
                    arrayOf("ic_miplay_headset", "ic_miplay_speaker", "ic_miplay_default")

                else -> arrayOf("ic_miplay_default", "ic_miplay_headset", "ic_miplay_speaker")
            }
        }

        private fun resolvePluginDrawable(names: Array<String>): Drawable {
            val cacheKey = names.joinToString("|")
            val id = pluginDrawableIdCache.getOrPut(cacheKey) {
                SystemUiVolumeEntryLayout.resourcePackages(targetContext.packageName)
                    .firstNotNullOfOrNull { packageName ->
                        names.firstNotNullOfOrNull { name ->
                            targetContext.resources.getIdentifier(name, "drawable", packageName)
                                .takeIf { it != 0 }
                        }
                    } ?: 0
            }
            if (id != 0) {
                val drawable = targetContext.resources.getDrawable(id, targetContext.theme)
                if (drawable != null) return drawable
                pluginDrawableIdCache.remove(cacheKey)
            }
            log(
                Log.WARN,
                TAG,
                "Plugin drawable missing, using default icon: ${names.joinToString()}",
                null,
            )
            return targetContext.packageManager.defaultActivityIcon
        }

        /**
         * 从官方插件资源读取 dimension（像素值）。
         *
         * 参考官方 `VolumeColumnRes.getIconSize` / `getIconMarginBottom`：应用图标与更多按钮
         * 的尺寸和 bottomMargin 直接复用官方 `o3_miui_volume_icon_*`，保证与侧边音量条里的
         * 图标定位一致。全部缺失时返回 null，调用方回退自身常量。
         */
        private fun resolvePluginDimension(names: Array<String>): Int? {
            names.forEach { name ->
                SystemUiVolumeEntryLayout.resourcePackages(targetContext.packageName)
                    .forEach { packageName ->
                        val id = targetContext.resources.getIdentifier(name, "dimen", packageName)
                        if (id != 0) {
                            val value = runCatching {
                                targetContext.resources.getDimensionPixelSize(id)
                            }.onFailure { error ->
                                log(
                                    Log.WARN,
                                    TAG,
                                    "Plugin dimen load failed name=$name package=$packageName",
                                    error
                                )
                            }.getOrNull()
                            if (value != null && value > 0) return value
                        }
                    }
            }
            log(Log.WARN, TAG, "Plugin dimen missing: ${names.joinToString()}", null)
            return null
        }

        private fun roundedBackground(color: Int, radius: Float): Drawable =
            GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(color)
                cornerRadius = radius
            }

        /**
         * 为更多/展开按钮应用与官方完全一致的取色（jadx 逆向 MiuiVolumeDialogView）：
         *
         * - updateExpandButtonTint：bionics 高级材质下 `setImageTintList(null)`（图标用
         *   drawable 原色，不额外上色）；否则用官方颜色资源 `getExpandedIconColorRes`
         *   对应的 blur 色（普通材质路径）。
         * - initExpandButtonBlend：高级材质下 `Util.setMiViewBlurAndBlendColor(button, 3,
         *   MiuiVolumeDialogRes.getExpandedIconBlandColor())` 让图标与玻璃背景融合
         *   （miuix ColorBlendToken 渲染，SoundMan 直接调用官方工具复刻）；否则
         *   `MiBlurCompat.setMiViewBlurModeCompat(button, 0)` 关闭 blur 走静态取色。
         *
         * blend 依赖 view 已 attach 到窗口（blur 采样），未 attach 时先只做 tint，
         * attach 后再补 blend。任何一步失败都回退静态 blur 色资源，绝不让取色失败
         * 拖垮面板。
         */
        private fun applyOfficialExpandButtonStyle(button: ImageView) {
            // bionics 判断与官方 updateExpandButtonTint 完全一致（Util.isBionicsAdvancedMaterialEnabled）。
            val bionics = runCatching {
                val util = pluginClassLoader.loadClass("com.android.systemui.miui.volume.Util")
                val method = util.methods.firstOrNull {
                    it.name == "isBionicsAdvancedMaterialEnabled" &&
                            it.parameterCount == 1 && it.parameterTypes[0] == Context::class.java
                }
                method?.invoke(null, targetContext) as? Boolean ?: false
            }.getOrDefault(false)
            if (bionics) {
                button.setImageTintList(null)
            } else {
                val tint = resolvePluginColor(MORE_BUTTON_COLOR_NAMES)
                    ?: Color.argb(0xCC, 0xFF, 0xFF, 0xFF)
                button.setImageTintList(ColorStateList.valueOf(tint))
            }
            if (button.isAttachedToWindow) {
                applyOfficialExpandButtonBlend(button)
            } else {
                button.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                    override fun onViewAttachedToWindow(v: View) {
                        button.removeOnAttachStateChangeListener(this)
                        applyOfficialExpandButtonBlend(button)
                    }

                    override fun onViewDetachedFromWindow(v: View) = Unit
                })
            }
        }

        /** 参考官方 MiuiVolumeDialogView.initExpandButtonBlend：高级材质走官方 blend，否则关 blur。 */
        private fun applyOfficialExpandButtonBlend(button: ImageView) {
            val advanced = runCatching {
                val util = pluginClassLoader.loadClass("com.android.systemui.miui.volume.Util")
                val method = util.methods.firstOrNull {
                    it.name == "isAdvancedMaterialEffective" &&
                            it.parameterCount == 1 && it.parameterTypes[0] == Context::class.java
                }
                method?.invoke(null, targetContext) as? Boolean ?: false
            }.getOrDefault(false)
            if (!advanced) {
                runCatching {
                    val compat = pluginClassLoader.loadClass("miui.systemui.util.MiBlurCompat")
                    compat.getMethod(
                        "setMiViewBlurModeCompat",
                        View::class.java,
                        Int::class.javaPrimitiveType
                    )
                        .invoke(null, button, 0)
                }.onFailure { error ->
                    log(Log.WARN, TAG, "Official blur-off failed for expand button", error)
                }
                return
            }
            runCatching {
                val res =
                    pluginClassLoader.loadClass("com.android.systemui.miui.volume.MiuiVolumeDialogRes")
                val blend = res.getMethod("getExpandedIconBlandColor").invoke(null)
                    ?: error("getExpandedIconBlandColor returned null")
                val util = pluginClassLoader.loadClass("com.android.systemui.miui.volume.Util")
                val setBlend = util.methods.firstOrNull {
                    it.name == "setMiViewBlurAndBlendColor" &&
                            it.parameterCount == 3 &&
                            it.parameterTypes[0] == View::class.java &&
                            it.parameterTypes[1] == Int::class.javaPrimitiveType
                } ?: error("Util.setMiViewBlurAndBlendColor missing")
                setBlend.invoke(null, button, 3, blend)
            }.onFailure { error ->
                log(
                    Log.WARN,
                    TAG,
                    "Official expand-button blend failed; falling back to static tint",
                    error
                )
                // blend 失败时回到静态取色，保证图标可见。
                val tint = resolvePluginColor(MORE_BUTTON_COLOR_NAMES)
                    ?: Color.argb(0xCC, 0xFF, 0xFF, 0xFF)
                button.setImageTintList(ColorStateList.valueOf(tint))
            }
        }

        /**
         * 从官方插件资源取颜色（官方普通材质图标取色路径）。
         *
         * 参考 jadx 逆向 `MiuiVolumeDialogRes.getExpandedIconColorRes`（needShowDialog=true 返回
         * `miui_volume_expand_button_color_blur_light`）与 `MiuiVolumeDialogView.updateExpandButtonTint`
         * （把该颜色资源 setImageTintList 到展开按钮）。SoundMan 的更多按钮使用同一套官方颜色资源，
         * 保证与侧边音量条官方取色一致：优先 blur_light / blur（与玻璃背景同源的 blur 混合色），
         * 回退 `miui_volume_expand_button_color_cc` 与 `vp_o3_volume_icon_normal`。
         * 全部缺失时返回 null，调用方回退半透明白兜底。
         */
        private fun resolvePluginColor(names: Array<String>): Int? {
            names.forEach { name ->
                SystemUiVolumeEntryLayout.resourcePackages(targetContext.packageName)
                    .forEach { packageName ->
                        val id = targetContext.resources.getIdentifier(name, "color", packageName)
                        if (id != 0) {
                            val color = runCatching {
                                targetContext.resources.getColor(id, targetContext.theme)
                            }.onFailure { error ->
                                log(
                                    Log.WARN,
                                    TAG,
                                    "Plugin color load failed name=$name package=$packageName",
                                    error
                                )
                            }.getOrNull()
                            if (color != null) return color
                        }
                    }
            }
            log(Log.WARN, TAG, "Plugin color missing: ${names.joinToString()}", null)
            return null
        }

        private fun dp(value: Int): Int =
            (value * targetContext.resources.displayMetrics.density + 0.5f).toInt()
    }

    private object OfficialVolumeDismissBridge {
        private const val OFFICIAL_DISMISS_REASON = 8
        private const val DIALOG_CONTROLLER_CLASS =
            "com.android.systemui.miui.volume.VolumePanelDialogController"
        private val cache = WeakHashMap<ViewGroup, List<DismissEntry>>()

        fun dismiss(
            dialog: ViewGroup,
            hookDismiss: () -> Boolean,
            log: (priority: Int, tag: String, message: String, throwable: Throwable?) -> Unit,
        ): Boolean {
            val entries = synchronized(cache) {
                cache[dialog] ?: bindEntries(dialog, log).also { bound ->
                    if (bound.isNotEmpty()) cache[dialog] = bound
                }
            }
            val entriesByType = entries.associateBy(DismissEntry::type)
            val order = SystemUiIndependentPanelPolicy.officialDismissOrder(
                hasViewControllerCallback = SystemUiOfficialDismissEntry.VIEW_CONTROLLER_CALLBACK in entriesByType,
                hasDialogEventListener = SystemUiOfficialDismissEntry.DIALOG_EVENT_LISTENER in entriesByType,
                hasHookController = true,
            )
            val succeeded = SystemUiOfficialDismissSequence.firstSuccessful(order) { type ->
                if (type == SystemUiOfficialDismissEntry.HOOK_CONTROLLER) {
                    val result = hookDismiss()
                    if (!result) log(Log.ERROR, TAG, "Official dismiss hook fallback failed", null)
                    result
                } else {
                    val entry =
                        checkNotNull(entriesByType[type]) { "Dismiss decision selected missing entry=$type" }
                    try {
                        entry.action()
                        true
                    } catch (throwable: Throwable) {
                        log(
                            Log.ERROR,
                            TAG,
                            "Official dismiss entry failed: ${entry.name}",
                            throwable
                        )
                        false
                    }
                }
            }
            if (succeeded == null) {
                log(
                    Log.ERROR,
                    TAG,
                    "All official volume dismiss entries and hook fallback failed",
                    null
                )
                return false
            }
            val name = entriesByType[succeeded]?.name ?: "hook-captured controller"
            log(Log.INFO, TAG, "Official volume dismissed through $name", null)
            return true
        }

        private fun bindEntries(
            dialog: ViewGroup,
            log: (priority: Int, tag: String, message: String, throwable: Throwable?) -> Unit,
        ): List<DismissEntry> {
            val motionCallback = runCatching { readField(dialog, "mCallback") }
                .onFailure {
                    log(
                        Log.ERROR,
                        TAG,
                        "Unable to bind MiuiVolumeDialogView motion callback",
                        it
                    )
                }
                .getOrNull() ?: return emptyList()
            val dialogController = runCatching {
                motionCallback.javaClass.declaredFields.firstNotNullOfOrNull { field ->
                    field.isAccessible = true
                    field.get(motionCallback)
                        ?.takeIf { it.javaClass.name == DIALOG_CONTROLLER_CLASS }
                } ?: error("VolumePanelDialogController owner was not found from motion callback")
            }.onFailure { log(Log.ERROR, TAG, "Unable to bind VolumePanelDialogController", it) }
                .getOrNull() ?: return emptyList()
            val entries = ArrayList<DismissEntry>(2)
            runCatching {
                val controllerCallback = readField(dialogController, "mCallback")
                    ?: error("VolumePanelDialogController.mCallback is null")
                val dismiss = controllerCallback.javaClass.methods.firstOrNull { method ->
                    method.name == "dismiss" && method.parameterCount == 1 &&
                            method.parameterTypes[0] == Int::class.javaPrimitiveType
                } ?: error("VolumePanelViewController callback dismiss(int) was not found")
                entries += DismissEntry(
                    SystemUiOfficialDismissEntry.VIEW_CONTROLLER_CALLBACK,
                    "view-controller callback",
                ) {
                    dismiss.invoke(controllerCallback, OFFICIAL_DISMISS_REASON)
                }
            }.onFailure {
                log(
                    Log.ERROR,
                    TAG,
                    "Unable to bind view-controller dismiss callback",
                    it
                )
            }
            runCatching {
                val dialogEventListener = readField(dialogController, "mDialogEventListener")
                    ?: error("VolumePanelDialogController.mDialogEventListener is null")
                val dismiss = dialogEventListener.javaClass.methods.firstOrNull { method ->
                    method.name == "dismiss" && method.parameterCount == 1 &&
                            method.parameterTypes[0] == Int::class.javaPrimitiveType
                } ?: error("VolumePanelDialog.DialogEventListener.dismiss(int) was not found")
                entries += DismissEntry(
                    SystemUiOfficialDismissEntry.DIALOG_EVENT_LISTENER,
                    "dialog-event listener",
                ) {
                    dismiss.invoke(dialogEventListener, OFFICIAL_DISMISS_REASON)
                }
            }.onFailure { log(Log.ERROR, TAG, "Unable to bind dialog-event dismiss callback", it) }
            return entries
        }

        private fun readField(owner: Any, name: String): Any? {
            var type: Class<*>? = owner.javaClass
            while (type != null) {
                val field = runCatching { type.getDeclaredField(name) }.getOrNull()
                if (field != null) {
                    field.isAccessible = true
                    return field.get(owner)
                }
                type = type.superclass
            }
            error("Field $name was not found on ${owner.javaClass.name}")
        }

        private data class DismissEntry(
            val type: SystemUiOfficialDismissEntry,
            val name: String,
            val action: () -> Unit,
        )
    }

    private class OfficialTouchInsetsRegistration(
        private val dialog: ViewGroup,
        private val log: (priority: Int, tag: String, message: String, throwable: Throwable?) -> Unit,
    ) {
        private val listenerType =
            Class.forName("android.view.ViewTreeObserver\$OnComputeInternalInsetsListener")
        private val fullFrameListener = Proxy.newProxyInstance(
            dialog.javaClass.classLoader,
            arrayOf(listenerType),
        ) { proxy, method, args ->
            when (method.name) {
                "onComputeInternalInsets" -> {
                    val info = args?.singleOrNull() ?: error("InternalInsetsInfo argument missing")
                    val intType =
                        Int::class.javaPrimitiveType ?: error("Int primitive type unavailable")
                    info.javaClass.getMethod("setTouchableInsets", intType)
                        .invoke(info, TOUCHABLE_INSETS_FRAME)
                    null
                }

                "toString" -> "SoundManFullWindowInsetsListener"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.singleOrNull()
                else -> null
            }
        }
        private var paused = false

        fun pause() {
            when (SystemUiIndependentPanelPolicy.insetsListenerAction(paused, panelActive = true)) {
                SystemUiInsetsListenerAction.NONE -> return
                SystemUiInsetsListenerAction.REMOVE -> Unit
                SystemUiInsetsListenerAction.ADD -> error("Unexpected touch listener action while mounting")
            }
            check(listenerType.isInstance(dialog)) {
                "MiuiVolumeDialogView does not implement OnComputeInternalInsetsListener: ${dialog.javaClass.name}"
            }
            updateRegistration(dialog, add = false)
            try {
                updateRegistration(fullFrameListener, add = true)
            } catch (throwable: Throwable) {
                updateRegistration(dialog, add = true)
                throw throwable
            }
            paused = true
            requestInsetsRecompute("paused with full-window touch frame")
        }

        fun restore() {
            when (SystemUiIndependentPanelPolicy.insetsListenerAction(
                paused,
                panelActive = false
            )) {
                SystemUiInsetsListenerAction.NONE -> return
                SystemUiInsetsListenerAction.ADD -> Unit
                SystemUiInsetsListenerAction.REMOVE -> error("Unexpected touch listener action while closing")
            }
            var removalFailure: Throwable? = null
            try {
                updateRegistration(fullFrameListener, add = false)
            } catch (throwable: Throwable) {
                removalFailure = throwable
                log(Log.ERROR, TAG, "Unable to remove full-window touch listener", throwable)
            }
            updateRegistration(dialog, add = true)
            paused = false
            requestInsetsRecompute("official touch region restored")
            removalFailure?.let {
                throw IllegalStateException(
                    "Full-window touch listener removal failed",
                    it
                )
            }
        }

        private fun updateRegistration(listener: Any, add: Boolean) {
            val observer = dialog.viewTreeObserver
            check(observer.isAlive) { "MiuiVolumeDialogView ViewTreeObserver is not alive" }
            val methodName = if (add) {
                "addOnComputeInternalInsetsListener"
            } else {
                "removeOnComputeInternalInsetsListener"
            }
            try {
                ViewTreeObserver::class.java.getMethod(methodName, listenerType)
                    .invoke(observer, listener)
            } catch (throwable: Throwable) {
                throw IllegalStateException(
                    "Unable to $methodName for ${listener.javaClass.name}",
                    throwable
                )
            }
        }

        private fun requestInsetsRecompute(reason: String) {
            dialog.requestLayout()
            dialog.rootView.requestLayout()
            dialog.rootView.invalidate()
            log(Log.DEBUG, TAG, "Internal touch insets $reason", null)
        }

        private companion object {
            const val TOUCHABLE_INSETS_FRAME = 0
        }
    }

    private class OfficialVolumeColumn private constructor(
        private val instance: Any,
        val view: View,
        val slider: SeekBar,
        private val icon: ImageView,
        private val progressView: View,
        private val progressViewBg: View,
        private val glassBg: View,
        private val expandBg: View,
        private val releaseMethod: Method,
    ) {
        fun release() {
            releaseMethod.invoke(instance)
        }

        fun prepareStandaloneColumn(
            packageName: String,
            log: (priority: Int, tag: String, message: String, throwable: Throwable?) -> Unit,
        ) {
            val duplicateRootBackground = view.background
            view.background = null
            icon.setImageDrawable(null)
            icon.imageTintList = null
            icon.background = null
            icon.visibility = View.INVISIBLE
            // 独立列里官方 slider 还带着折叠态的灰色圆角底 + 玻璃模糊层（slider_bg_glass /
            // slider_bg_blend），叠加在全窗口透明 host 上会形成“音量条后面的灰色背景阴影边框”。
            // 保留滑块本体与进度填充（progressDrawable / progressView），只移除多余背景层。
            glassBg.background = null
            expandBg.background = null
            progressViewBg.background = null
            slider.background = null
            log(
                Log.INFO,
                TAG,
                "VolumeColumn layers package=$packageName root=${duplicateRootBackground?.javaClass?.name ?: "none"} " +
                        "slider=${slider.background?.javaClass?.name ?: "none"} " +
                        "progress=${progressView.background?.javaClass?.name ?: "none"} " +
                        "progressBg=${progressViewBg.background?.javaClass?.name ?: "none"} " +
                        "glass=${glassBg.background?.javaClass?.name ?: "none"} " +
                        "expand=${expandBg.background?.javaClass?.name ?: "none"}; cleared=background-layers",
                null,
            )
        }

        companion object {
            /**
             * 给独立列安装真实 SeekBarAnimListener，把官方 MiuiVolumeSeekBar 的
             * SlideContainerAnim（Folme）拖动动画映射到本列 view 的 scale/translationY。
             *
             * 动机：官方控制器（VolumePanelViewController）通过 initAnimListener 注册回调，
             * 驱动 ringer/expand/dnd 等整体动画。独立列没有这些区域，若像以前那样装 no-op
             * listener，官方 dispatchTouchEvent 的 ACTION_MOVE 每帧仍会 cancel+重启 Folme
             * 动画（getHeightArray 全 0 + 全部回调空转），UI 线程被持续占用，表现为“拖动
             * 十几秒才有反应且完全没有动画”。这里把动画目标收敛到 column.view，
             * 让官方拖动手势（按压缩放、拖动位移）在本列上真实回放。
             *
             * @param slider 官方 MiuiVolumeSeekBar（SeekBarAnimListener 集合里的 setSeekBarAnimListener）
             * @param columnView 本列根 view，动画真正作用的载体
             * @param classLoader 官方类加载器（用于创建 Proxy）
             * @param onFailure 回调执行失败时的上报
             */
            private fun installColumnAnimListener(
                slider: SeekBar,
                columnView: View,
                classLoader: ClassLoader,
                onFailure: (Throwable) -> Unit,
            ) {
                val setter = slider.javaClass.methods.firstOrNull { method ->
                    method.name == "setSeekBarAnimListener" && method.parameterCount == 1
                } ?: error("MiuiVolumeSeekBar.setSeekBarAnimListener was not found")
                val listenerType = setter.parameterTypes.single()
                check(listenerType.isInterface) {
                    "MiuiVolumeSeekBar SeekBarAnimListener is not an interface: ${listenerType.name}"
                }
                val listener = Proxy.newProxyInstance(
                    classLoader,
                    arrayOf(listenerType),
                ) { proxy, method, arguments ->
                    try {
                        when (method.name) {
                            "toString" -> "SoundManColumnAnimListener"
                            "hashCode" -> System.identityHashCode(proxy)
                            "equals" -> proxy === arguments?.singleOrNull()
                            // 官方 getHeightArray 返回 {topMargin, topMargin, topMargin, ringerDivider}；
                            // 独立列无 ringer/dnd 区域，提供 slider 自身高度保证位移计算不越界。
                            "getHeightArray" -> intArrayOf(0, 0, 0, columnView.height)
                            "resetView" -> {
                                columnView.scaleX = 1f
                                columnView.scaleY = 1f
                                columnView.translationY = 0f
                                null
                            }

                            "setScale" -> {
                                val before = arguments?.getOrNull(0) as? Float
                                val after = arguments?.getOrNull(1) as? Float
                                if (before == null || after == null) {
                                    defaultValue(method.returnType)
                                } else {
                                    val delta = after - before
                                    columnView.scaleX += delta
                                    columnView.scaleY += delta
                                    null
                                }
                            }

                            "setVolY" -> {
                                val before = arguments?.getOrNull(0) as? Float
                                val after = arguments?.getOrNull(1) as? Float
                                if (before == null || after == null) {
                                    defaultValue(method.returnType)
                                } else {
                                    columnView.translationY += after - before
                                    null
                                }
                            }
                            // 独立列没有 ringer/dnd/superVolume 区域，这些动画目标在官方
                            // VolumePanelViewController 布局上，这里保持空实现。
                            "setRingerY", "setDndY", "setSuperVolumeY" -> null
                            else -> defaultValue(method.returnType)
                        }
                    } catch (throwable: Throwable) {
                        onFailure(throwable)
                        defaultValue(method.returnType)
                    }
                }
                setter.invoke(slider, listener)
            }

            private fun defaultValue(type: Class<*>): Any? = when (type) {
                java.lang.Boolean.TYPE -> false
                java.lang.Byte.TYPE -> 0.toByte()
                java.lang.Short.TYPE -> 0.toShort()
                java.lang.Integer.TYPE -> 0
                java.lang.Long.TYPE -> 0L
                java.lang.Float.TYPE -> 0f
                java.lang.Double.TYPE -> 0.0
                java.lang.Character.TYPE -> '\u0000'
                else -> null
            }

            fun create(
                classLoader: ClassLoader,
                context: Context,
                parent: ViewGroup,
                fakeStream: Int,
                row: LoadedAppRow,
                onTrackingChanged: (Boolean) -> Unit,
                onVolumeCommitted: (SystemUiBuiltinAppRowState, Int) -> Unit,
                onFailure: (Throwable) -> Unit,
            ): OfficialVolumeColumn {
                val columnClass = Class.forName(VOLUME_COLUMN_CLASS, true, classLoader)
                val column = columnClass.getConstructor().newInstance()
                val booleanType =
                    Boolean::class.javaPrimitiveType ?: error("Boolean primitive type unavailable")
                val intType =
                    Int::class.javaPrimitiveType ?: error("Int primitive type unavailable")
                columnClass.getMethod(
                    "initColumn",
                    Context::class.java,
                    ViewGroup::class.java,
                    intType,
                    booleanType,
                    booleanType,
                    booleanType,
                ).invoke(column, context, parent, fakeStream, true, true, false)
                // 该版本 initColumn 会在绑定 icon 前调用 setExpanded；必须先以折叠态完成 View 初始化，
                // 再切到展开态，否则 observable 回调会访问尚未初始化的 icon。
                columnClass.getMethod("setExpanded", booleanType).invoke(column, true)
                columnClass.getMethod("setSize", booleanType, booleanType)
                    .invoke(column, true, false)
                columnClass.getMethod("setSliderResource", booleanType).invoke(column, true)
                columnClass.getMethod("setSliderBlendColor", booleanType).invoke(column, false)
                columnClass.getMethod("onMaterialModeChanged").invoke(column)

                val view = columnClass.getMethod("getView").invoke(column) as? View
                    ?: error("VolumeColumn.getView returned non-View")
                val slider = columnClass.getMethod("getSlider").invoke(column) as? SeekBar
                    ?: error("VolumeColumn.getSlider returned non-SeekBar")
                val icon = columnClass.getMethod("getIcon").invoke(column) as? ImageView
                    ?: error("VolumeColumn.getIcon returned non-ImageView")
                installColumnAnimListener(slider, view, classLoader, onFailure)
                val progressView = columnClass.getMethod("getProgressView").invoke(column) as? View
                    ?: error("VolumeColumn.getProgressView returned non-View")
                val progressViewBg =
                    columnClass.getMethod("getProgressViewBg").invoke(column) as? View
                        ?: error("VolumeColumn.getProgressViewBg returned non-View")
                val glassBg = columnClass.getMethod("getGlassBg").invoke(column) as? View
                    ?: error("VolumeColumn.getGlassBg returned non-View")
                val expandBg = columnClass.getMethod("getExpandBg").invoke(column) as? View
                    ?: error("VolumeColumn.getExpandBg returned non-View")
                val updateSliderRatio = columnClass.getMethod("updateSliderRatio")
                val setTracking = columnClass.getMethod("setTracking", booleanType)
                val setMaxLevel = progressView.javaClass.getMethod("setMaxLevel", intType)
                val setVolumeLevel = progressView.javaClass.getMethod(
                    "setVolumeLevel",
                    Float::class.javaPrimitiveType
                )
                val updateProgressHeight =
                    progressView.javaClass.getMethod("updateProgressHeight", SeekBar::class.java)

                icon.contentDescription = null
                slider.max = SystemUiOfficialSliderProgress.MAX
                slider.progress =
                    SystemUiOfficialSliderProgress.fromPercent(row.state.volumePercent)
                slider.contentDescription = row.state.label
                setMaxLevel.invoke(progressView, 100)
                setVolumeLevel.invoke(progressView, row.state.volumePercent.toFloat())
                updateProgressHeight.invoke(progressView, slider)
                updateSliderRatio.invoke(column)
                val dragSession = SystemUiSliderDragSession()
                // 拖动/点击过程中同步官方进度填充层高度，避免滑块动了但填充层滞后的“假卡顿”。
                val refreshProgressView = { currentProgress: Int ->
                    try {
                        val percent =
                            SystemUiOfficialSliderProgress.toPercent(currentProgress).toFloat()
                        setVolumeLevel.invoke(progressView, percent)
                        updateProgressHeight.invoke(progressView, slider)
                    } catch (throwable: Throwable) {
                        onFailure(throwable)
                    }
                }
                slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(
                        seekBar: SeekBar,
                        progress: Int,
                        fromUser: Boolean
                    ) {
                        if (fromUser) {
                            dragSession.move()
                            // 拖动中实时刷新官方进度填充层，否则进度条视觉完全不跟随手势，
                            // 配合上面的动画 listener 恢复拖动反馈。
                            refreshProgressView(progress)
                        }
                    }

                    override fun onStartTrackingTouch(seekBar: SeekBar) {
                        try {
                            dragSession.start()
                            setTracking.invoke(column, true)
                            onTrackingChanged(true)
                        } catch (throwable: Throwable) {
                            onTrackingChanged(false)
                            onFailure(throwable)
                        }
                    }

                    override fun onStopTrackingTouch(seekBar: SeekBar) {
                        try {
                            val action = dragSession.stop(seekBar.progress) { finalProgress ->
                                onVolumeCommitted(
                                    row.state,
                                    SystemUiOfficialSliderProgress.toPercent(finalProgress),
                                )
                            }
                            if (action != SystemUiSliderCommitAction.COMMIT_FINAL) return
                            setTracking.invoke(column, false)
                            onTrackingChanged(false)
                        } catch (throwable: Throwable) {
                            onTrackingChanged(false)
                            onFailure(throwable)
                        }
                    }
                })
                return OfficialVolumeColumn(
                    instance = column,
                    view = view,
                    slider = slider,
                    icon = icon,
                    progressView = progressView,
                    progressViewBg = progressViewBg,
                    glassBg = glassBg,
                    expandBg = expandBg,
                    releaseMethod = columnClass.getMethod("release"),
                )
            }
        }
    }

    private data class AppColumnBuild(
        val view: View,
        val columnWidth: Int,
        val columnHeight: Int,
    )

    private data class AppsPageBuild(
        val view: View,
        val columnWidth: Int,
        val columnHeight: Int,
    )

    private data class LoadedAppRow(
        val state: SystemUiBuiltinAppRowState,
        val protocolRow: PanelPlaybackRow,
        val icon: Drawable,
    )

    private data class LoadedSnapshot(
        val snapshot: PanelPlaybackSnapshot,
        val apps: List<LoadedAppRow>
    )

    companion object {
        private const val TAG = "SoundMan.BuiltinPanel"
        private const val HOST_TAG = "hk.uwu.soundman:independent_volume_host"
        private const val PANEL_TAG = "hk.uwu.soundman:independent_volume_panel"
        private const val VOLUME_DIALOG_VIEW_CLASS =
            "com.android.systemui.miui.volume.MiuiVolumeDialogView"
        private const val VOLUME_COLUMN_CLASS = "com.android.systemui.miui.volume.VolumeColumn"
        private const val PANEL_EDGE_MARGIN_DP = 12
        private const val PANEL_HORIZONTAL_PADDING_DP = 8
        private const val PANEL_VERTICAL_PADDING_DP = 8
        private const val NAVIGATION_WIDTH_DP = 48
        private const val COLUMN_SPACING_DP = 4
        private const val APP_ICON_SIZE_DP = 32
        private const val APP_ICON_RASTER_SIZE_DP = 96
        private const val APP_ICON_SLOT_HEIGHT_DP = 44
        private const val MIN_ACTION_SIZE_DP = 40
        private const val ACTION_SLOT_HEIGHT_DP = 48
        private const val ACTION_SPACING_DP = 8
        private const val ACTION_INSET_MARGIN_DP = 8

        // 应用图标尺寸（音量条内部底部展示）。
        private const val INNER_ICON_SIZE_DP = 26
        private const val INNER_ICON_PADDING_DP = 6

        // 更多按钮触摸判定（参考官方 MiuiVolumeSeekBar.doClick）：
        // 按下后移动超过该阈值视为拖动（转交 slider），否则按“快速点击”处理。
        private const val MORE_BUTTON_TOUCH_SLOP_DP = 8

        // 快速点击时间上限（官方为 200ms）。
        private const val MORE_BUTTON_CLICK_TIMEOUT_MILLIS = 200L
        private const val HEADER_ACTION_SIZE_DP = 36
        private const val HEADER_ICON_SIZE_DP = 26
        private const val MIN_COLUMN_WIDTH_DP = 64
        private const val MAX_COLUMN_WIDTH_DP = 104
        private const val APP_COLUMN_MIN_WIDTH_DP = 84
        private const val MIN_COLUMN_HEIGHT_DP = 220
        private const val MAX_COLUMN_HEIGHT_DP = 420
        private const val EMPTY_CONTENT_WIDTH_DP = 180
        private const val EMPTY_CONTENT_BODY_HEIGHT_DP = 96
        private const val DEVICE_PAGE_WIDTH_DP = 340
        private const val DEVICE_PAGE_HEADER_HEIGHT_DP = 48
        private const val DEVICE_PAGE_HORIZONTAL_PADDING_DP = 8

        // 设备行内左右留白：图标与圆角边框之间需要更宽的呼吸距离，独立于页面横向 padding。
        private const val DEVICE_ROW_HORIZONTAL_PADDING_DP = 16
        private const val DEVICE_PAGE_VERTICAL_PADDING_DP = 14
        private const val DEVICE_PAGE_ROW_SPACING_DP = 10
        private const val DEVICE_ROW_HEIGHT_DP = 62

        // 设备行数超出扫描设备数（deviceCount）的固定预留行：只补 1 行「跟随系统」。
        // 面板高度按真实行数自适应：行少则矮，行多则跟随变高，超过屏幕封顶后交给
        // ScrollView 滚动（底部自然露出半行，提示用户可继续滚动查看）。
        private const val DEVICE_PAGE_RESERVED_ROWS = 1
        private const val PER_USER_RANGE = 100_000
        private const val PAGE_ANIMATION_DURATION_MILLIS = 350L
        private const val EXPAND_ANIMATION_DURATION_MILLIS = 470L

        // 关闭滑走动画时长（官方 hide 通过 folme 响应因子控制，约 200-300ms 级别）。
        private const val HIDE_SLIDE_ANIMATION_DURATION_MILLIS = 320L

        // 官方 VolumeShowHideAnimator dismiss 动画时长（folme spring，官方埋点
        // trackVolumePanelAnimEnd 使用 467L 常量）。SoundMan 关闭时需等官方 dismiss
        // 动画把 dialog X 移到屏幕外后再恢复 dialog 可见状态，避免侧边栏阴影闪现。
        private const val OFFICIAL_DISMISS_ANIMATION_MILLIS = 467L
        private const val COLUMN_TRANSLATION_Z_DP = 20
        private const val POLL_INTERVAL_MILLIS = 750L
        private val EXPAND_INTERPOLATOR = PathInterpolator(0.2f, 0f, 0f, 1f)

        // 参考官方 MiuiVolumeDialogMotion 收起（VolumeShowHideAnimator.hide）使用的
        // SpringInterpolator 弹簧手感：阻尼弹簧曲线（快速起步、平滑加速、末端收敛）比
        // 线性 PathInterpolator 更接近官方 folme 动画的丝滑度。
        // 参数经曲线验证：damping=1.0, stiffness=2.5（stiffness > damping²），scale=3
        // 使动画结束时位移到达 100%，单调无突兀回弹。
        private val HIDE_INTERPOLATOR = SpringTimeInterpolator(damping = 1.0f, stiffness = 2.5f)
        private val BACK_ICON_NAMES = arrayOf(
            "ic_arrow_back",
            "miuix_appcompat_action_mode_back_arrow",
            "miuix_appcompat_ic_action_bar_back",
            "ic_miui_volume_collapse",
            "ic_miui_volume_expand",
        )
        private val MORE_ICON_NAMES = arrayOf(
            "ic_miui_volume_more",
            "ic_miui_volume_expand",
            "ic_miui_volume_collapse",
        )

        // 参考官方 MiuiVolumeDialogRes.getExpandedIconColorRes：needShowDialog=true 时展开按钮
        // 取色 = miui_volume_expand_button_color_blur_light，否则 = ..._blur——与玻璃背景同源的
        // blur 混合色（官方高级材质走 ColorBlendToken，blur 色资源是 SoundMan 可用的静态近似）。
        // 缺失再回退 blur 色 cc 变体与 VolumeColumn 图标系列（normal），全部缺失由调用方兜底。
        private val MORE_BUTTON_COLOR_NAMES = arrayOf(
            "miui_volume_expand_button_color_blur_light",
            "miui_volume_expand_button_color_blur",
            "miui_volume_expand_button_color_cc",
            "vp_o3_volume_icon_normal",
        )
        private val sessions = WeakHashMap<ViewGroup, Session>()

        private fun dimension(vararg candidates: Int?): Int =
            candidates.firstOrNull { it != null && it > 0 }
                ?: error("Required live View dimension is unavailable")
    }
}

/**
 * 阻尼弹簧插值器，模拟 MIUI SpringInterpolator（damping, stiffness）的曲线。
 *
 * 官方音量收起动画（VolumeShowHideAnimator.hide）使用 SpringInterpolator 弹簧手感
 * （compileSdk 不含该 API，故在此以阻尼弹簧公式复刻）：快速起步、平滑加速、末端
 * 收敛，比线性/PathInterpolator 更接近官方 folme 动画的丝滑度。
 *
 * 公式：x(t) = 1 - e^(-damping·t) · (cos(ωd·t) + (damping/ωd)·sin(ωd·t))，
 * 其中 ωd = sqrt(stiffness - damping²)，t = input × scale（scale 把动画时长映射到
 * 弹簧收敛区间，保证动画结束时位移到达 100%）。参数需满足 stiffness > damping²。
 */
private class SpringTimeInterpolator(
    private val damping: Float,
    private val stiffness: Float,
    private val scale: Float = 3f,
) : android.animation.TimeInterpolator {
    private val omegaD = kotlin.math.sqrt((stiffness - damping * damping).coerceAtLeast(1e-6f))

    override fun getInterpolation(input: Float): Float {
        val t = input.coerceIn(0f, 1f) * scale
        val decay = kotlin.math.exp(-damping * t)
        return (1f - decay * (kotlin.math.cos(omegaD * t) + (damping / omegaD) * kotlin.math.sin(
            omegaD * t
        )))
            .coerceIn(0f, 1f)
    }
}
