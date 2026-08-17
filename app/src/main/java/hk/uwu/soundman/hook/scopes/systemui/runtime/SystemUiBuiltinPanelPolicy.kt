package hk.uwu.soundman.hook.scopes.systemui.runtime

import hk.uwu.soundman.data.PanelPlaybackSnapshot
import hk.uwu.soundman.ipc.SoundManProtocol

/** SoundMan 入口点击后实际采用的展示路径。 */
enum class SystemUiVolumePanelPath { OVERLAY, BUILTIN }

/**
 * SystemUI 音量入口路径策略。
 *
 * 关闭实验开关时保持旧悬浮层；开启后仅在内置页完整挂载成功时使用内置页，否则立即回退悬浮层。
 */
object SystemUiBuiltinPanelPolicy {
    fun open(
        builtinEnabled: Boolean,
        mountBuiltin: () -> Boolean,
        openOverlay: () -> Unit,
    ): SystemUiVolumePanelPath {
        if (builtinEnabled && mountBuiltin()) return SystemUiVolumePanelPath.BUILTIN
        openOverlay()
        return SystemUiVolumePanelPath.OVERLAY
    }
}

/** 原生列表的纯状态模型，集中约束排序和音量范围。 */
data class SystemUiBuiltinAppRowState(
    val packageName: String,
    val label: String,
    val uid: Int,
    val volumePercent: Int,
) {
    init {
        require(packageName.isNotBlank()) { "packageName must not be blank" }
        require(label.isNotBlank()) { "label must not be blank" }
        require(uid >= 0) { "uid must be non-negative" }
        require(volumePercent in 0..100) { "volumePercent must be in 0..100" }
    }
}

object SystemUiBuiltinPanelState {
    fun sorted(rows: List<SystemUiBuiltinAppRowState>): List<SystemUiBuiltinAppRowState> =
        rows.distinctBy { it.uid }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
}

/** 应用列刷新时的稳定集合差异，package name 是列的唯一标识。 */
data class SystemUiAppColumnTransition(
    val retained: Set<String>,
    val entering: Set<String>,
    val exiting: Set<String>,
)

object SystemUiAppColumnTransitions {
    fun resolve(previous: Set<String>, next: Set<String>): SystemUiAppColumnTransition {
        require(previous.none(String::isBlank) && next.none(String::isBlank)) {
            "app column packages must not be blank"
        }
        return SystemUiAppColumnTransition(
            retained = previous intersect next,
            entering = next - previous,
            exiting = previous - next,
        )
    }
}

object SystemUiOfficialDismissReasons {
    const val TIMEOUT = 3
    const val SCREEN_OFF = 4
}

/** 精确复刻 VolumePanelViewController.dismissH 的 isShown 前置条件。 */
object SystemUiOfficialDismissGate {
    fun accepts(
        controllerShowing: Boolean,
        needsDialog: Boolean,
        dialogShown: Boolean,
    ): Boolean = !(controllerShowing && needsDialog && !dialogShown)
}

enum class SystemUiOfficialDismissSessionAction {
    CLOSE_FROM_OFFICIAL_ANIMATED,
    CLOSE_FROM_OFFICIAL_IMMEDIATELY,
    IGNORE_ALREADY_CLOSING,
}

enum class SystemUiOfficialShadowAction { RESTORE, KEEP_INVISIBLE }

object SystemUiOfficialShadowPolicy {
    fun action(
        externalDismiss: Boolean,
        dialogParentVisible: Boolean,
    ): SystemUiOfficialShadowAction =
        if (externalDismiss && dialogParentVisible) {
            SystemUiOfficialShadowAction.KEEP_INVISIBLE
        } else {
            SystemUiOfficialShadowAction.RESTORE
        }
}

object SystemUiExternalDismissCompletionPolicy {
    /** 只有官方父容器已隐藏后才能恢复自建前保存的 dialog 状态。 */
    fun restoreOriginalDialogState(action: SystemUiOfficialDismissCompletionAction): Boolean =
        when (action) {
            SystemUiOfficialDismissCompletionAction.COMPLETE -> true
            SystemUiOfficialDismissCompletionAction.FORCE_COMPLETE -> false
            SystemUiOfficialDismissCompletionAction.WAIT -> error("External dismiss must not finalize while waiting")
        }
}

/** 官方 controller 已经开始关闭时，自建 session 只能清理自身，不能递归再次 dismissH。 */
object SystemUiOfficialDismissSessionPolicy {
    fun action(reason: Int, sessionClosing: Boolean): SystemUiOfficialDismissSessionAction = when {
        sessionClosing -> SystemUiOfficialDismissSessionAction.IGNORE_ALREADY_CLOSING
        reason == SystemUiOfficialDismissReasons.SCREEN_OFF ->
            SystemUiOfficialDismissSessionAction.CLOSE_FROM_OFFICIAL_IMMEDIATELY

        else -> SystemUiOfficialDismissSessionAction.CLOSE_FROM_OFFICIAL_ANIMATED
    }
}

data class SystemUiPanelRect(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    init {
        require(right > left) { "rect width must be positive" }
        require(bottom > top) { "rect height must be positive" }
    }

    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

data class SystemUiHitRect(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    init {
        require(right >= left) { "hit rect width must not be negative" }
        require(bottom >= top) { "hit rect height must not be negative" }
    }

    val width: Int get() = right - left
    val height: Int get() = bottom - top

    fun intersects(other: SystemUiHitRect): Boolean =
        left < other.right && right > other.left && top < other.bottom && bottom > other.top
}

enum class SystemUiPanelHit { INSIDE, OUTSIDE }

data class SystemUiCompactPanelLayout(
    val width: Int,
    val height: Int,
    val visibleColumns: Int,
    val scrollable: Boolean,
)

/** 展开卡片中音量列的对称内容边距几何。 */
data class SystemUiSymmetricColumnInsets(
    val horizontal: Int,
    val vertical: Int,
) {
    init {
        require(horizontal >= 0 && vertical >= 0) { "column insets must not be negative" }
        require(horizontal == vertical) { "volume column insets must be symmetric" }
    }
}

data class SystemUiColumnRegions(
    val slider: SystemUiHitRect,
    val bottomIcon: SystemUiHitRect,
    val more: SystemUiHitRect,
    val device: SystemUiHitRect,
) {
    /**
     * 内嵌覆盖层之间是否互相重叠。
     *
     * slider 是音量条本体，more/device/bottomIcon 是参考官方 VolumeColumn 叠在
     * slider 内部的操作区/图标层，与 slider 必然相交（这正是“写在音量条里面”）。
     * 需要保证的只是这几个覆盖层彼此不抢占同一块触摸区域。
     */
    fun hasOverlap(): Boolean {
        val overlays = listOf(bottomIcon, more, device)
        return overlays.indices.any { first ->
            (first + 1 until overlays.size).any { second -> overlays[first].intersects(overlays[second]) }
        }
    }
}

enum class SystemUiAppIconSource { SYSTEM_UI_PACKAGE, LAUNCHER_APPS, PROVIDER_PAYLOAD, DEFAULT_ICON }

object SystemUiAppIconPolicy {
    fun choose(
        packageDrawableAvailable: Boolean,
        launcherDrawableAvailable: Boolean,
        providerPayloadAvailable: Boolean,
    ): SystemUiAppIconSource = when {
        packageDrawableAvailable -> SystemUiAppIconSource.SYSTEM_UI_PACKAGE
        launcherDrawableAvailable -> SystemUiAppIconSource.LAUNCHER_APPS
        providerPayloadAvailable -> SystemUiAppIconSource.PROVIDER_PAYLOAD
        else -> SystemUiAppIconSource.DEFAULT_ICON
    }
}

data class SystemUiIndependentPanelAnimationSpec(
    val folded: SystemUiPanelRect,
    val expanded: SystemUiPanelRect,
)

data class SystemUiIndependentDismissTransform(
    val translationX: Float,
    val translationY: Float,
    val scale: Float,
)

data class SystemUiVolumeColumnLayerState(
    val alpha: Float,
    val scale: Float,
    val translationZFraction: Float,
)

enum class SystemUiInsetsListenerAction { NONE, REMOVE, ADD }

enum class SystemUiIndependentBackAction { SHOW_OVERVIEW, CLOSE_PANEL }

data class SystemUiIndependentCloseState(
    val terminal: Boolean,
    val reopenFallback: Boolean,
    val dismissOfficialSession: Boolean,
)

enum class SystemUiSliderCommitAction { NONE, COMMIT_LIVE, COMMIT_FINAL }

enum class SystemUiOfficialDismissEntry { VIEW_CONTROLLER_CALLBACK, DIALOG_EVENT_LISTENER, HOOK_CONTROLLER }

enum class SystemUiOfficialDismissCompletionAction { WAIT, COMPLETE, FORCE_COMPLETE }

/**
 * 官方 dismissH completion 的一次性门闩。
 *
 * 关闭动画是异步的；门闩把启动代次绑定到回调代次，避免重复回调或旧 session 回调
 * 在新状态上执行 host/材质/触摸区域清理。
 */
class SystemUiOfficialDismissCompletionGate {
    private var startedGeneration: Long? = null
    private var completed = false

    @Synchronized
    fun begin(generation: Long): Boolean {
        if (startedGeneration != null || completed) return false
        startedGeneration = generation
        return true
    }

    @Synchronized
    fun complete(generation: Long, currentGeneration: Long): Boolean {
        if (completed || startedGeneration != generation || generation != currentGeneration) return false
        completed = true
        return true
    }
}

object SystemUiOfficialDismissSequence {
    fun firstSuccessful(
        order: List<SystemUiOfficialDismissEntry>,
        attempt: (SystemUiOfficialDismissEntry) -> Boolean,
    ): SystemUiOfficialDismissEntry? {
        order.forEach { entry -> if (attempt(entry)) return entry }
        return null
    }
}

data class SystemUiColumnPixelSizes(
    val actionSize: Int,
    val actionSpacing: Int,
    val iconSize: Int,
    val iconSlotHeight: Int,
    val actionSlotHeight: Int,
    val minimumColumnWidth: Int,
) {
    init {
        require(actionSize > 0 && iconSize > 0) { "column controls must have positive size" }
        require(actionSpacing >= 0) { "action spacing must not be negative" }
        require(iconSlotHeight >= iconSize) { "icon slot must contain icon" }
        require(actionSlotHeight >= actionSize) { "action slot must contain actions" }
        require(minimumColumnWidth > 0) { "minimum column width must be positive" }
    }

    val actionContentWidth: Int = actionSize * 2 + actionSpacing

    fun wrapperWidth(officialWidth: Int): Int {
        require(officialWidth > 0) { "official width must be positive" }
        return maxOf(officialWidth, actionContentWidth, minimumColumnWidth)
    }

    companion object {
        fun fromDensity(
            density: Float,
            actionSizeDp: Int = 40,
            actionSpacingDp: Int = 8,
            iconSizeDp: Int = 32,
            iconSlotHeightDp: Int = 44,
            actionSlotHeightDp: Int = 48,
            minimumColumnWidthDp: Int = 72,
        ): SystemUiColumnPixelSizes {
            require(density > 0f && density.isFinite()) { "density must be finite and positive" }
            fun px(dp: Int): Int = (dp * density + 0.5f).toInt()
            return SystemUiColumnPixelSizes(
                actionSize = px(actionSizeDp),
                actionSpacing = px(actionSpacingDp),
                iconSize = px(iconSizeDp),
                iconSlotHeight = px(iconSlotHeightDp),
                actionSlotHeight = px(actionSlotHeightDp),
                minimumColumnWidth = px(minimumColumnWidthDp),
            )
        }
    }
}

object SystemUiDrawablePixelVisibility {
    fun hasVisiblePixel(width: Int, height: Int, pixels: IntArray): Boolean {
        require(width > 0 && height > 0) { "bitmap size must be positive" }
        require(pixels.size == width * height) { "pixel buffer does not match bitmap size" }
        return pixels.any { pixel -> pixel ushr 24 != 0 }
    }
}

class SystemUiSliderDragSession {
    private var tracking = false
    private var lastCommittedLevel: Int? = null

    /**
     * 记录拖动开始时已经生效的等级。
     *
     * 官方 VolumeSeekBarChangeListener 会在每次用户进度跨到新等级时立即下发音量，停止拖动仅做
     * 状态收尾；这里保留相同语义，避免把所有声音变化推迟到 ACTION_UP。
     */
    fun start(initialLevel: Int) {
        require(initialLevel >= 0) { "initialLevel must not be negative" }
        tracking = true
        lastCommittedLevel = initialLevel
    }

    fun move(level: Int, commit: (Int) -> Unit): SystemUiSliderCommitAction =
        commitChangedLevel(level, SystemUiSliderCommitAction.COMMIT_LIVE, commit)

    fun stop(finalLevel: Int, commit: (Int) -> Unit): SystemUiSliderCommitAction {
        if (!tracking) return SystemUiSliderCommitAction.NONE
        val action = commitChangedLevel(finalLevel, SystemUiSliderCommitAction.COMMIT_FINAL, commit)
        tracking = false
        return action
    }

    private fun commitChangedLevel(
        level: Int,
        action: SystemUiSliderCommitAction,
        commit: (Int) -> Unit,
    ): SystemUiSliderCommitAction {
        require(level >= 0) { "level must not be negative" }
        if (!tracking || level == lastCommittedLevel) return SystemUiSliderCommitAction.NONE
        lastCommittedLevel = level
        commit(level)
        return action
    }
}

data class SystemUiPanelSnapshotFingerprint(
    val status: String,
    val rows: List<String>,
)

/** 独立 sibling 页的纯布局与快照策略，不读取或改变官方 controller 状态。 */
object SystemUiIndependentPanelPolicy {
    fun symmetricColumnInsets(contentInset: Int): SystemUiSymmetricColumnInsets {
        require(contentInset >= 0) { "contentInset must not be negative" }
        return SystemUiSymmetricColumnInsets(contentInset, contentInset)
    }

    /** 保留官方测得的 VolumeColumn 高度，不施加额外最小/最大卡片高度。 */
    fun officialColumnHeight(measuredHeight: Int, maximumHeight: Int): Int {
        require(measuredHeight > 0 && maximumHeight > 0) { "column heights must be positive" }
        require(measuredHeight <= maximumHeight) { "official column must fit the panel" }
        return measuredHeight
    }

    fun compactLayout(
        appCount: Int,
        availableWidth: Int,
        availableHeight: Int,
        columnWidth: Int,
        columnHeight: Int,
        navigationWidth: Int,
        horizontalPadding: Int,
        verticalPadding: Int,
        columnSpacing: Int,
        headerHeight: Int,
        edgeMargin: Int,
        emptyContentWidth: Int,
    ): SystemUiCompactPanelLayout {
        require(appCount >= 0) { "appCount must not be negative" }
        require(availableWidth > edgeMargin * 2) { "availableWidth must exceed edge margins" }
        require(availableHeight > edgeMargin * 2) { "availableHeight must exceed edge margins" }
        require(columnWidth > 0 && columnHeight > 0) { "column size must be positive" }
        require(navigationWidth >= 0) { "navigationWidth must not be negative" }
        require(horizontalPadding >= 0 && verticalPadding >= 0) { "panel padding must not be negative" }
        require(columnSpacing >= 0 && headerHeight >= 0) { "column spacing and header height must not be negative" }
        require(edgeMargin >= 0 && emptyContentWidth > 0) { "edge margin and empty width are invalid" }

        val maximumWidth = availableWidth - edgeMargin * 2
        val maximumHeight = availableHeight - edgeMargin * 2
        val fixedWidth = horizontalPadding * 2 + navigationWidth
        val availableColumnsWidth = (maximumWidth - fixedWidth).coerceAtLeast(columnWidth)
        val maximumVisibleColumns =
            ((availableColumnsWidth + columnSpacing) / (columnWidth + columnSpacing))
                .coerceAtLeast(1)
        val visibleColumns = when {
            appCount == 0 -> 0
            else -> appCount.coerceAtMost(maximumVisibleColumns)
        }
        val contentWidth = if (appCount == 0) {
            emptyContentWidth
        } else {
            visibleColumns * columnWidth + (visibleColumns - 1).coerceAtLeast(0) * columnSpacing
        }
        val wrappedWidth = fixedWidth + contentWidth
        val scrollable = appCount > maximumVisibleColumns
        return SystemUiCompactPanelLayout(
            width = if (scrollable) maximumWidth else wrappedWidth.coerceAtMost(maximumWidth),
            height = (verticalPadding * 2 + headerHeight + columnHeight).coerceAtMost(maximumHeight),
            visibleColumns = visibleColumns,
            scrollable = scrollable,
        )
    }

    fun hitTest(panel: SystemUiPanelRect, x: Int, y: Int): SystemUiPanelHit =
        if (x >= panel.left && x < panel.right && y >= panel.top && y < panel.bottom) {
            SystemUiPanelHit.INSIDE
        } else {
            SystemUiPanelHit.OUTSIDE
        }

    /**
     * 单一展开按钮的列宽。
     *
     * 官方 VolumeColumn 只需容纳 slider；SoundMan 当前仅保留一个顶部按钮，不能继续使用
     * 双按钮 actionContentWidth，否则会凭空扩大左右留白。
     */
    fun singleActionColumnWidth(officialWidth: Int, actionSize: Int): Int {
        require(officialWidth > 0 && actionSize > 0) { "column dimensions must be positive" }
        return maxOf(officialWidth, actionSize)
    }

    fun columnRegions(
        columnWidth: Int,
        sliderWidth: Int,
        sliderHeight: Int,
        sizes: SystemUiColumnPixelSizes,
    ): SystemUiColumnRegions {
        require(columnWidth > 0 && sliderWidth > 0 && sliderHeight > 0) { "slider slot size must be positive" }
        require(sliderWidth <= columnWidth && sizes.iconSize <= columnWidth) { "column content must fit its width" }
        require(sizes.actionContentWidth <= columnWidth) { "action controls must fit without overlap" }

        // 参考官方 VolumeColumn：更多/设备按钮与应用图标作为音量条内部的内嵌覆盖层，
        // 按钮在内部顶部、图标在内部底部，全部落在 slider 竖直区间内，不做外部槽位。
        val sliderLeft = (columnWidth - sliderWidth) / 2
        val actionTop = (sizes.actionSlotHeight - sizes.actionSize) / 2
        val actionLeft = (columnWidth - sizes.actionContentWidth) / 2
        val iconTop =
            sliderHeight - sizes.iconSlotHeight + (sizes.iconSlotHeight - sizes.iconSize) / 2
        return SystemUiColumnRegions(
            slider = SystemUiHitRect(sliderLeft, 0, sliderLeft + sliderWidth, sliderHeight),
            bottomIcon = SystemUiHitRect(
                (columnWidth - sizes.iconSize) / 2,
                iconTop,
                (columnWidth + sizes.iconSize) / 2,
                iconTop + sizes.iconSize,
            ),
            more = SystemUiHitRect(
                actionLeft,
                actionTop,
                actionLeft + sizes.actionSize,
                actionTop + sizes.actionSize,
            ),
            device = SystemUiHitRect(
                actionLeft + sizes.actionSize + sizes.actionSpacing,
                actionTop,
                actionLeft + sizes.actionContentWidth,
                actionTop + sizes.actionSize,
            ),
        ).also { check(!it.hasOverlap()) { "column regions overlap" } }
    }

    fun animationSpec(
        folded: SystemUiPanelRect,
        expandedWidth: Int,
        expandedHeight: Int,
        parentWidth: Int,
        parentHeight: Int = Int.MAX_VALUE,
        edgeMargin: Int = 0,
    ): SystemUiIndependentPanelAnimationSpec {
        require(expandedWidth > 0 && expandedHeight > 0) { "expanded size must be positive" }
        require(parentWidth > 0 && parentHeight > 0) { "parent size must be positive" }
        require(edgeMargin >= 0) { "edgeMargin must not be negative" }
        require(expandedWidth <= parentWidth - edgeMargin * 2) { "expanded width exceeds parent bounds" }
        require(expandedHeight <= parentHeight - edgeMargin * 2) { "expanded height exceeds parent bounds" }
        val anchoredRight = folded.left + folded.width / 2 >= parentWidth / 2
        val requestedLeft = if (anchoredRight) folded.right - expandedWidth else folded.left
        val expandedLeft =
            requestedLeft.coerceIn(edgeMargin, parentWidth - edgeMargin - expandedWidth)
        // 展开面板与折叠音量条保持平行：垂直位置锚定折叠入口顶部，不做屏幕级居中，
        // 否则面板会从音量条旁边被移走（用户反馈"卡片位置被往下移"）。
        // 面板内部音量条的上下对称由列轨道高度（trackHeight）保证，与面板在屏幕
        // 上的位置无关；这里只负责让卡片出现在音量条旁的正确位置。
        val expandedTop =
            folded.top.coerceIn(edgeMargin, parentHeight - edgeMargin - expandedHeight)
        return SystemUiIndependentPanelAnimationSpec(
            folded = folded,
            expanded = SystemUiPanelRect(
                left = expandedLeft,
                top = expandedTop,
                right = expandedLeft + expandedWidth,
                bottom = expandedTop + expandedHeight,
            ),
        )
    }

    fun interpolateRect(
        spec: SystemUiIndependentPanelAnimationSpec,
        fraction: Float
    ): SystemUiPanelRect {
        require(fraction in 0f..1f) { "fraction must be in 0..1" }
        fun lerp(start: Int, end: Int): Int = (start + (end - start) * fraction).toInt()
        return SystemUiPanelRect(
            left = lerp(spec.folded.left, spec.expanded.left),
            top = lerp(spec.folded.top, spec.expanded.top),
            right = lerp(spec.folded.right, spec.expanded.right),
            bottom = lerp(spec.folded.bottom, spec.expanded.bottom),
        )
    }

    /**
     * 计算独立 panel 的官方 hide 风格终态：沿屏幕水平边缘滑出，同时向下移动并缩放到 0.8。
     * 该变换只服务于自建 panel；官方 dialog 的真正结束仍由 dismissH completion 决定。
     */
    fun dismissTransform(
        panel: SystemUiPanelRect,
        rootWidth: Int,
        fraction: Float,
    ): SystemUiIndependentDismissTransform {
        require(rootWidth > 0) { "rootWidth must be positive" }
        require(fraction in 0f..1f) { "fraction must be in 0..1" }
        val anchoredRight = panel.left + panel.width / 2 >= rootWidth / 2
        val targetX = if (anchoredRight) rootWidth + panel.width else -(panel.width * 2)
        return SystemUiIndependentDismissTransform(
            translationX = targetX * fraction,
            translationY = panel.height / 3f * fraction,
            scale = 1f - 0.2f * fraction,
        )
    }

    fun volumeColumnNode(index: Int): Float {
        require(index >= 0) { "column index must be non-negative" }
        return when (index) {
            0 -> 0.3f
            1 -> 0.5f
            else -> (0.6f + (index - 2) * 0.1f).coerceAtMost(0.9f)
        }
    }

    fun volumeColumnLayerState(index: Int, panelFraction: Float): SystemUiVolumeColumnLayerState {
        require(panelFraction in 0f..1f) { "panelFraction must be in 0..1" }
        val node = volumeColumnNode(index)
        val local = ((panelFraction - node) / (1f - node)).coerceIn(0f, 1f)
        return SystemUiVolumeColumnLayerState(
            alpha = local,
            scale = 0.6f + 0.4f * local,
            translationZFraction = 1f - local,
        )
    }

    fun insetsListenerAction(
        listenerPaused: Boolean,
        panelActive: Boolean
    ): SystemUiInsetsListenerAction = when {
        panelActive && !listenerPaused -> SystemUiInsetsListenerAction.REMOVE
        !panelActive && listenerPaused -> SystemUiInsetsListenerAction.ADD
        else -> SystemUiInsetsListenerAction.NONE
    }

    fun closeState(closeReason: String): SystemUiIndependentCloseState {
        require(closeReason.isNotBlank()) { "closeReason must not be blank" }
        val fallbackFailure =
            closeReason.contains("failure") || closeReason.contains("unavailable") ||
                    closeReason.contains("rejected")
        return SystemUiIndependentCloseState(
            terminal = true,
            reopenFallback = fallbackFailure,
            dismissOfficialSession = closeReason !in setOf(
                "mount failure",
                "entry cleanup",
                "panel detached"
            ),
        )
    }

    fun pageBackAction(inDetails: Boolean): SystemUiIndependentBackAction =
        if (inDetails) SystemUiIndependentBackAction.SHOW_OVERVIEW else SystemUiIndependentBackAction.CLOSE_PANEL

    fun sliderCommitAction(tracking: Boolean, stopTracking: Boolean): SystemUiSliderCommitAction =
        when {
            stopTracking -> SystemUiSliderCommitAction.COMMIT_FINAL
            tracking -> SystemUiSliderCommitAction.COMMIT_LIVE
            else -> SystemUiSliderCommitAction.NONE
        }

    fun officialDismissOrder(
        hasViewControllerCallback: Boolean,
        hasDialogEventListener: Boolean,
        hasHookController: Boolean,
    ): List<SystemUiOfficialDismissEntry> = buildList {
        if (hasViewControllerCallback) add(SystemUiOfficialDismissEntry.VIEW_CONTROLLER_CALLBACK)
        if (hasDialogEventListener) add(SystemUiOfficialDismissEntry.DIALOG_EVENT_LISTENER)
        if (hasHookController) add(SystemUiOfficialDismissEntry.HOOK_CONTROLLER)
    }

    /**
     * 决定官方 reason=8 dismiss 的完成时机。
     *
     * JADX 已确认原版 `setVolumeDialogVisible(false, …)` 会把 MiuiVolumeDialogView 的直接父
     * View 设为 INVISIBLE，且展开页的 `collapse(false, …)` 在该状态后同步收尾。该父容器
     * 隐藏是可靠完成信号；超时仅处理 ROM 异常导致官方状态机未落位的场景。
     */
    fun officialDismissCompletionAction(
        dialogParentVisible: Boolean,
        elapsedMillis: Long,
        timeoutMillis: Long,
    ): SystemUiOfficialDismissCompletionAction {
        require(elapsedMillis >= 0L) { "elapsedMillis must not be negative" }
        require(timeoutMillis > 0L) { "timeoutMillis must be positive" }
        return when {
            !dialogParentVisible -> SystemUiOfficialDismissCompletionAction.COMPLETE
            elapsedMillis >= timeoutMillis -> SystemUiOfficialDismissCompletionAction.FORCE_COMPLETE
            else -> SystemUiOfficialDismissCompletionAction.WAIT
        }
    }

    /**
     * 外部 timeout/touch dismiss 由 VolumePanelDialog.dismiss() 直接 detach Window；此时旧的
     * View parent 可能仍是 VISIBLE，必须以 dialog.isShown() 为完成信号。
     */
    fun externalOfficialDismissCompletionAction(
        dialogShown: Boolean,
        elapsedMillis: Long,
        timeoutMillis: Long,
    ): SystemUiOfficialDismissCompletionAction {
        require(elapsedMillis >= 0L) { "elapsedMillis must not be negative" }
        require(timeoutMillis > 0L) { "timeoutMillis must be positive" }
        return when {
            !dialogShown -> SystemUiOfficialDismissCompletionAction.COMPLETE
            elapsedMillis >= timeoutMillis -> SystemUiOfficialDismissCompletionAction.FORCE_COMPLETE
            else -> SystemUiOfficialDismissCompletionAction.WAIT
        }
    }

    fun fingerprint(snapshot: PanelPlaybackSnapshot): SystemUiPanelSnapshotFingerprint =
        SystemUiPanelSnapshotFingerprint(
            status = snapshot.status.name,
            rows = buildList {
                addAll(
                    snapshot.rows
                        .sortedWith(compareBy({ it.uid }, { it.packageName }))
                        .map { row ->
                            val target = SoundManProtocol.encodeTargetIdentity(row.outputTarget)
                            val iconHash = row.iconPng?.contentHashCode() ?: 0
                            "app:${row.uid}:${row.packageName}:${row.volumePercent}:$target:" +
                                    "${row.followsSystemAfterDisconnect}:${row.label.orEmpty()}:$iconHash"
                        })
                addAll(
                    snapshot.devices.map(SoundManProtocol::encodeDeviceIdentity).sorted()
                        .map { "device:$it" })
            },
        )
}

/** 为独立页应用列分配不会与 Android 音频流常量冲突的稳定 View id/stream。 */
object SystemUiFakeStreamAllocator {
    private const val FIRST_FAKE_STREAM = 10_000
    private const val LAST_FAKE_STREAM = 999_999

    fun allocate(packageNames: List<String>): Map<String, Int> {
        require(packageNames.none(String::isBlank)) { "package names must not be blank" }
        val used = HashSet<Int>()
        return packageNames.distinct().sorted().associateWith { packageName ->
            var candidate = FIRST_FAKE_STREAM + (packageName.hashCode() and Int.MAX_VALUE) %
                    (LAST_FAKE_STREAM - FIRST_FAKE_STREAM + 1)
            while (!used.add(candidate)) {
                candidate = if (candidate == LAST_FAKE_STREAM) FIRST_FAKE_STREAM else candidate + 1
            }
            candidate
        }
    }
}

/** 官方 slider 以 1000 为 max，面板规则仍保持 0..100。 */
object SystemUiOfficialSliderProgress {
    const val MAX = 1_000

    fun fromPercent(percent: Int): Int {
        require(percent in 0..100) { "percent must be in 0..100" }
        return percent * (MAX / 100)
    }

    fun toPercent(progress: Int): Int {
        require(progress in 0..MAX) { "progress must be in 0..$MAX" }
        return ((progress * 100f) / MAX).toInt().coerceIn(0, 100)
    }
}
