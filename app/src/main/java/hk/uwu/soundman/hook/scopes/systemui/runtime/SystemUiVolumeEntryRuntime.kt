package hk.uwu.soundman.hook.scopes.systemui.runtime

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Outline
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import hk.uwu.soundman.R
import hk.uwu.soundman.hook.scopes.systemui.hidden.OfficialRingerBlur
import hk.uwu.soundman.overlay.OverlayOpenRequest
import java.lang.ref.WeakReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock

/** 在 HyperOS 紧凑音量侧栏的音量条上方插入 SoundMan 圆钮入口。 */
class SystemUiVolumeEntryRuntime(
    private val log: (priority: Int, tag: String, message: String, throwable: Throwable?) -> Unit,
) {
    private val trackedEntries = ArrayList<TrackedEntry>()
    private val pendingInsertions = ArrayList<PendingInsertion>()
    private val closing = AtomicBoolean(false)
    private val lastTriggerLogMillis = AtomicLong()
    private val lastDelayLogMillis = AtomicLong()
    private val lifecycleLock = ReentrantLock()
    private val insertionsIdle = lifecycleLock.newCondition()
    private var activeInsertions = 0
    private var officialBlur: OfficialRingerBlur? = null

    /**
     * 插件 ClassLoader 就绪后安装 live MiBlur 入口。
     *
     * `MiBlurCompat` / `Util` 只在插件 ClassLoader 里。
     */
    fun attachPluginClassLoader(pluginClassLoader: ClassLoader) {
        officialBlur = OfficialRingerBlur(pluginClassLoader, log)
    }

    /** 在各目标 View 所属 UI Looper 上同步移除入口；失败时保留追踪状态供后续重试。 */
    fun cleanupInsertedEntries(): Boolean {
        val alreadyClosing = withLifecycleLock {
            if (closing.get()) {
                true
            } else {
                closing.set(true)
                false
            }
        }
        if (alreadyClosing) return false

        return try {
            if (!awaitActiveInsertions()) {
                cancelCleanup()
                false
            } else {
                val pendingSnapshot = synchronized(pendingInsertions) { pendingInsertions.toList() }
                val entrySnapshot = synchronized(trackedEntries) { trackedEntries.toList() }
                val pendingCleaned = pendingSnapshot.map(::cancelPendingOnUiLooper).all { it }
                val entriesCleaned = entrySnapshot.map { entry ->
                    val view = entry.view.get()
                    view == null || cleanupOnUiLooper(entry.uiLooper, view)
                }.all { it }
                if (!pendingCleaned || !entriesCleaned) {
                    cancelCleanup()
                    false
                } else {
                    val pendingRemain = synchronized(pendingInsertions) {
                        pendingInsertions.removeAll(pendingSnapshot.toSet())
                        pendingInsertions.isNotEmpty()
                    }
                    val trackedEntriesRemain = synchronized(trackedEntries) {
                        trackedEntries.removeAll(entrySnapshot.toSet())
                        trackedEntries.removeAll { it.view.get() == null }
                        trackedEntries.isNotEmpty()
                    }
                    if (pendingRemain || trackedEntriesRemain) {
                        log(Log.ERROR, TAG, "Volume entry cleanup left callbacks, listeners, or buttons behind", null)
                        cancelCleanup()
                        false
                    } else {
                        true
                    }
                }
            }
        } catch (throwable: Throwable) {
            cancelCleanup()
            log(Log.ERROR, TAG, "Failed to clean up SoundMan volume entries", throwable)
            false
        }
    }

    private fun awaitActiveInsertions(): Boolean {
        val deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(CLEANUP_TIMEOUT_MILLIS)
        withLifecycleLock {
            while (activeInsertions != 0) {
                val remainingNanos = deadlineNanos - System.nanoTime()
                if (remainingNanos <= 0L) {
                    log(Log.ERROR, TAG, "Timed out waiting for volume entry insertion", null)
                    return false
                }
                try {
                    insertionsIdle.awaitNanos(remainingNanos)
                } catch (interrupted: InterruptedException) {
                    Thread.currentThread().interrupt()
                    log(Log.ERROR, TAG, "Interrupted while waiting for volume entry insertion", interrupted)
                    return false
                }
            }
        }
        return true
    }

    /** 取消 teardown，使旧 hook 在 cleanup 失败后继续工作。 */
    fun cancelCleanup() {
        withLifecycleLock {
            closing.set(false)
            if (activeInsertions < 0) activeInsertions = 0
            insertionsIdle.signalAll()
        }
    }

    private inline fun <T> withLifecycleLock(block: () -> T): T {
        lifecycleLock.lock()
        try {
            return block()
        } finally {
            lifecycleLock.unlock()
        }
    }

    private fun cancelPendingOnUiLooper(pending: PendingInsertion): Boolean {
        val cancel = Runnable { cancelPending(pending) }
        if (Looper.myLooper() === pending.uiLooper) {
            cancel.run()
            return true
        }

        val completed = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val task = Runnable {
            try {
                cancel.run()
            } catch (throwable: Throwable) {
                failure.set(throwable)
            } finally {
                completed.countDown()
            }
        }
        val handler = Handler(pending.uiLooper)
        if (!handler.post(task)) {
            log(Log.ERROR, TAG, "Target UI Looper rejected pending volume entry callback cleanup", null)
            return false
        }
        return try {
            if (!completed.await(CLEANUP_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                handler.removeCallbacks(task)
                log(Log.ERROR, TAG, "Timed out cleaning pending volume entry callback", null)
                false
            } else {
                val throwable = failure.get()
                if (throwable == null) true else {
                    log(Log.ERROR, TAG, "Failed to clean pending volume entry callback", throwable)
                    false
                }
            }
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            handler.removeCallbacks(task)
            log(Log.ERROR, TAG, "Interrupted cleaning pending volume entry callback", interrupted)
            false
        }
    }

    private fun cleanupOnUiLooper(uiLooper: Looper, entry: View): Boolean {
        if (Looper.myLooper() === uiLooper) return cleanupEntry(entry)

        val completed = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val taskState = AtomicReference(CleanupTaskState.PENDING)
        val cleanupTask = Runnable {
            synchronized(taskState) {
                if (taskState.get() == CleanupTaskState.CANCELLED) {
                    completed.countDown()
                    return@Runnable
                }
                taskState.set(CleanupTaskState.RUNNING)
            }
            try {
                if (!cleanupEntry(entry)) {
                    failure.set(IllegalStateException("SoundMan volume entry cleanup failed"))
                }
            } catch (throwable: Throwable) {
                failure.set(throwable)
            } finally {
                synchronized(taskState) { taskState.set(CleanupTaskState.FINISHED) }
                completed.countDown()
            }
        }
        val handler = Handler(uiLooper)
        if (!handler.post(cleanupTask)) {
            log(Log.ERROR, TAG, "Target UI Looper rejected SoundMan volume entry cleanup", null)
            return false
        }
        var interrupted = false
        try {
            var timedOut = false
            val completedWithinTimeout = try {
                completed.await(CLEANUP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            } catch (_: InterruptedException) {
                interrupted = true
                false
            }
            if (!completedWithinTimeout) {
                val cancelled = synchronized(taskState) {
                    if (taskState.get() == CleanupTaskState.PENDING) {
                        taskState.set(CleanupTaskState.CANCELLED)
                        true
                    } else {
                        false
                    }
                }
                if (cancelled) {
                    handler.removeCallbacks(cleanupTask)
                    log(Log.ERROR, TAG, "Cancelled queued SoundMan volume entry cleanup", null)
                    return false
                }

                timedOut = !interrupted
                while (true) {
                    try {
                        completed.await()
                        break
                    } catch (_: InterruptedException) {
                        interrupted = true
                    }
                }
            }
            if (timedOut) {
                log(Log.ERROR, TAG, "Running SoundMan volume entry cleanup exceeded timeout and completed", null)
            }
            val throwable = failure.get() ?: return true
            log(Log.ERROR, TAG, "Failed to clean up SoundMan volume entry", throwable)
            return false
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
    }

    private fun cleanupEntry(entry: View): Boolean {
        return try {
            (entry.parent as? ViewGroup)?.removeView(entry)
            entry.setOnClickListener(null)
            clearVisuals(entry)
            entry.contentDescription = null
            entry.tag = null
            true
        } catch (throwable: Throwable) {
            log(Log.ERROR, TAG, "Failed to clean up SoundMan volume entry", throwable)
            false
        }
    }

    private fun clearVisuals(view: View) {
        view.background = null
        view.outlineProvider = null
        view.clipToOutline = false
        if (view is ImageView) {
            view.setImageDrawable(null)
        }
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                clearVisuals(view.getChildAt(index))
            }
        }
    }

    private fun beginInsertion(): Boolean {
        withLifecycleLock {
            if (closing.get()) return false
            activeInsertions += 1
            return true
        }
    }

    private fun endInsertion() {
        withLifecycleLock {
            activeInsertions -= 1
            insertionsIdle.signalAll()
        }
    }

    private fun track(entry: View, uiLooper: Looper): Boolean {
        withLifecycleLock {
            if (closing.get()) return false
            synchronized(trackedEntries) {
                if (trackedEntries.none { it.view.get() === entry }) {
                    trackedEntries += TrackedEntry(WeakReference(entry), uiLooper)
                }
            }
            return true
        }
    }

    /**
     * 从 SystemUI/插件进程打开 SoundMan 面板，并关掉音量侧栏。
     */
    fun openOverlay(context: Context, trigger: String, sourceView: View) {
        if (closing.get()) return
        val intent = Intent(ACTION_OPEN_OVERLAY)
            .setComponent(ComponentName(MODULE_PACKAGE, MAIN_ACTIVITY_CLASS))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        OverlayOpenRequest(fromVolumeSidebar = true).putInto(intent)
        try {
            log(Log.INFO, TAG, "[systemui] $trigger startActivity begin component=${intent.component}", null)
            context.startActivity(intent)
            log(Log.INFO, TAG, "[systemui] $trigger startActivity dispatched component=${intent.component}", null)
            dismissVolumeSidebar(sourceView)
        } catch (error: ActivityNotFoundException) {
            log(Log.ERROR, TAG, "SoundMan overlay activity was not found trigger=$trigger", error)
        } catch (error: SecurityException) {
            log(Log.ERROR, TAG, "SystemUI is not allowed to open the SoundMan overlay trigger=$trigger", error)
        } catch (error: RuntimeException) {
            log(Log.ERROR, TAG, "Unable to open the SoundMan overlay trigger=$trigger", error)
        }
    }

    private fun dismissVolumeSidebar(sourceView: View) {
        val root = sourceView.rootView
        if (root == null) {
            log(Log.ERROR, TAG, "Volume sidebar dismiss skipped: rootView is null", null)
            return
        }
        OverlayOpenRequest.volumeSidebarDismissSequence().forEach { stroke ->
            val dispatched = try {
                root.dispatchKeyEvent(KeyEvent(stroke.action, stroke.keyCode))
            } catch (error: RuntimeException) {
                log(
                    Log.ERROR,
                    TAG,
                    "Volume sidebar dismiss dispatch failed action=${stroke.action} keyCode=${stroke.keyCode}",
                    error,
                )
                return@forEach
            }
            if (!dispatched) {
                log(
                    Log.ERROR,
                    TAG,
                    "Volume sidebar dismiss was not handled action=${stroke.action} keyCode=${stroke.keyCode}",
                    null,
                )
            }
        }
    }

    /**
     * 音量面板展开时隐藏只有紧凑态的第三颗入口。
     *
     * 找不到入口只打日志，不得把异常打穿 SystemUI。
     */
    fun applyExpanded(root: View?, expanded: Boolean) {
        if (root == null) {
            log(Log.ERROR, TAG, "Volume expand update skipped: root is not a View", null)
            return
        }
        val entry = findInsertedEntry(root)
        if (entry == null) {
            log(
                Log.WARN,
                TAG,
                "Volume entry not found for expanded=$expanded root=${describeView(root)}",
                null,
            )
            return
        }
        entry.visibility = SystemUiVolumeEntryLayout.entryVisibility(expanded)
    }

    private fun findInsertedEntry(root: View): View? = findExistingEntry(root)

    fun scheduleInsertion(thisObject: Any?, trigger: String) {
        if (closing.get()) return
        val root = thisObject as? View
        if (root == null) {
            log(
                Log.ERROR,
                TAG,
                "Volume insertion skipped: trigger=$trigger target is not a View: ${thisObject?.javaClass?.name}",
                null,
            )
            return
        }
        logRateLimited(
            lastTriggerLogMillis,
            "[systemui] trigger=$trigger root=${describeView(root)} attached=${root.isAttachedToWindow}",
        )
        val uiLooper = root.handler?.looper ?: Looper.myLooper()
        if (uiLooper == null) {
            log(Log.ERROR, TAG, "Volume insertion skipped: trigger=$trigger has no UI Looper", null)
            return
        }
        val resolved = resolveAnchor(root)
        if (resolved == null && root.isAttachedToWindow && isReady(root)) {
            log(Log.ERROR, TAG, "Volume anchor not found under ${describeView(root)} trigger=$trigger", null)
            return
        }

        synchronized(pendingInsertions) {
            pendingInsertions.filter { it.root.get() === root }.forEach(::cancelPending)
            pendingInsertions.removeAll { it.root.get() == null || it.cancelled.get() }
        }

        val pending = PendingInsertion(
            WeakReference(root),
            resolved?.view?.let { WeakReference(it) },
            resolved?.name,
            uiLooper,
        )
        val attempt = Runnable {
            pending.postQueued.set(false)
            if (pending.cancelled.get() || closing.get()) return@Runnable
            val currentRoot = pending.root.get() ?: return@Runnable removePending(pending)
            val currentAnchor = pending.anchor?.get() ?: resolveAnchor(currentRoot)?.also { found ->
                pending.anchor = WeakReference(found.view)
                pending.anchorName = found.name
            }?.view
            if (currentAnchor == null) {
                if (currentRoot.isAttachedToWindow && isReady(currentRoot)) {
                    log(Log.ERROR, TAG, "Volume anchor not found under ${describeView(currentRoot)} trigger=$trigger", null)
                    cancelPending(pending)
                    removePending(pending)
                } else {
                    logRateLimited(
                        lastDelayLogMillis,
                        "[systemui] delaying insertion trigger=$trigger root=${describeView(currentRoot)} attached=${currentRoot.isAttachedToWindow} laidOut=${currentRoot.isLaidOut}",
                    )
                }
                return@Runnable
            }
            if (!currentRoot.isAttachedToWindow || !isReady(currentAnchor)) {
                logRateLimited(
                    lastDelayLogMillis,
                    "[systemui] delaying insertion trigger=$trigger root=${describeView(currentRoot)} target=${describeView(currentAnchor)} attached=${currentRoot.isAttachedToWindow} laidOut=${currentAnchor.isLaidOut} size=${currentAnchor.measuredWidth}x${currentAnchor.measuredHeight}",
                )
                return@Runnable
            }
            if (!beginInsertion()) return@Runnable
            try {
                if (!closing.get() && !pending.cancelled.get()) {
                    insertEntry(
                        currentRoot,
                        currentAnchor,
                        pending.anchorName ?: "unknown",
                        trigger,
                        log,
                        { closing.get() },
                        ::track,
                        ::cleanupEntry,
                        ::openOverlay,
                        officialBlur,
                    )
                }
            } catch (throwable: Throwable) {
                log(Log.ERROR, TAG, "Failed to add SoundMan volume entry", throwable)
            } finally {
                endInsertion()
                cancelPending(pending)
                removePending(pending)
            }
        }
        pending.postTask = attempt
        pending.layoutListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            queueInsertionAttempt(pending)
        }
        val listenTarget = resolved?.view ?: root
        listenTarget.addOnLayoutChangeListener(pending.layoutListener)
        synchronized(pendingInsertions) {
            if (closing.get()) {
                cancelPending(pending)
                return
            }
            pendingInsertions += pending
        }
        log(
            Log.DEBUG,
            TAG,
            "$trigger queued SoundMan volume entry until ${pending.anchorName ?: "root"} is measured",
            null,
        )
        queueInsertionAttempt(pending)
    }

    private fun resolveAnchor(root: View): AnchorMatch? = findAnchorByResource(root)

    private fun findAnchorByResource(root: View): AnchorMatch? {
        val packages = SystemUiVolumeEntryLayout.resourcePackages(root.context.packageName)
        var current = root.parent as? View ?: return null
        while (true) {
            SystemUiVolumeEntryLayout.VOLUME_COLUMN_RESOURCE_NAMES.forEach { name ->
                val view = findViewByIdName(current, name, packages, log)
                if (view != null && !isWithinRoot(view, root)) {
                    log(
                        Log.INFO,
                        TAG,
                        "Found volume column id=$name view=${describeView(view)} from=${describeView(root)}",
                        null,
                    )
                    return AnchorMatch(view, "id:$name")
                }
            }
            if (current.javaClass.name == VOLUME_DIALOG_VIEW_CLASS) {
                log(
                    Log.ERROR,
                    TAG,
                    "Volume column not found under $VOLUME_DIALOG_VIEW_CLASS from ${describeView(root)}",
                    null,
                )
                return null
            }
            current = current.parent as? View ?: break
        }
        log(
            Log.ERROR,
            TAG,
            "Volume column not found; stopped above ${describeView(root)} without reaching $VOLUME_DIALOG_VIEW_CLASS",
            null,
        )
        return null
    }

    private fun isReady(view: View): Boolean =
        view.isLaidOut && view.measuredWidth > 0 && view.measuredHeight > 0

    private fun queueInsertionAttempt(pending: PendingInsertion) {
        if (pending.cancelled.get() || closing.get() || !pending.postQueued.compareAndSet(false, true)) return
        val root = pending.root.get()
        val task = pending.postTask
        if (root == null || task == null || !root.post(task)) {
            pending.postQueued.set(false)
            log(Log.ERROR, TAG, "Unable to queue SoundMan volume entry insertion on target root", null)
            cancelPending(pending)
            removePending(pending)
        }
    }

    private fun cancelPending(pending: PendingInsertion) {
        pending.cancelled.set(true)
        val root = pending.root.get()
        val task = pending.postTask
        if (root != null && task != null) root.removeCallbacks(task)
        pending.postQueued.set(false)
        val listener = pending.layoutListener
        val listenTarget = pending.anchor?.get() ?: pending.root.get()
        if (listenTarget != null && listener != null) listenTarget.removeOnLayoutChangeListener(listener)
        pending.postTask = null
        pending.layoutListener = null
    }

    private fun removePending(pending: PendingInsertion) {
        synchronized(pendingInsertions) { pendingInsertions.remove(pending) }
    }

    private fun logRateLimited(clock: AtomicLong, message: String) {
        val now = android.os.SystemClock.elapsedRealtime()
        val previous = clock.get()
        if (now - previous >= REPEATED_LOG_INTERVAL_MILLIS && clock.compareAndSet(previous, now)) {
            log(Log.DEBUG, TAG, message, null)
        }
    }

    private fun describeView(view: View): String =
        "${view.javaClass.name}@${Integer.toHexString(System.identityHashCode(view))} id=${view.id}"

    private data class TrackedEntry(
        val view: WeakReference<View>,
        val uiLooper: Looper,
    )

    private class PendingInsertion(
        val root: WeakReference<View>,
        var anchor: WeakReference<View>?,
        var anchorName: String?,
        val uiLooper: Looper,
    ) {
        val cancelled = AtomicBoolean(false)
        val postQueued = AtomicBoolean(false)
        var postTask: Runnable? = null
        var layoutListener: View.OnLayoutChangeListener? = null
    }

    private data class AnchorMatch(
        val view: View,
        val name: String,
    )

    companion object {
        private const val TAG = "SoundMan.SystemUi"
        private const val MODULE_PACKAGE = "hk.uwu.soundman"
        private const val MAIN_ACTIVITY_CLASS = "hk.uwu.soundman.MainActivity"
        private const val ACTION_OPEN_OVERLAY = "hk.uwu.soundman.action.OPEN_OVERLAY"
        private const val ENTRY_TAG = "hk.uwu.soundman:volume_entry"
        private const val VOLUME_DIALOG_VIEW_CLASS =
            "com.android.systemui.miui.volume.MiuiVolumeDialogView"
        private const val CLEANUP_TIMEOUT_SECONDS = 5L
        private const val CLEANUP_TIMEOUT_MILLIS = CLEANUP_TIMEOUT_SECONDS * 1000L
        private const val REPEATED_LOG_INTERVAL_MILLIS = 2_000L

        private enum class CleanupTaskState {
            PENDING,
            RUNNING,
            FINISHED,
            CANCELLED,
        }


        private fun insertEntry(
            root: View,
            anchor: View,
            anchorName: String,
            trigger: String,
            log: (priority: Int, tag: String, message: String, throwable: Throwable?) -> Unit,
            isClosing: () -> Boolean,
            track: (View, Looper) -> Boolean,
            cleanup: (View) -> Boolean,
            openOverlay: (Context, String, View) -> Unit,
            officialBlur: OfficialRingerBlur?,
        ) {
            if (isClosing()) return
            if (!anchor.isLaidOut || anchor.measuredWidth <= 0 || anchor.measuredHeight <= 0) {
                log(Log.ERROR, TAG, "Volume anchor is not ready for measured insertion: $anchorName", null)
                return
            }
            val targetContext = root.context
            val uiLooper = root.handler?.looper ?: Looper.myLooper()
            if (uiLooper == null) {
                log(Log.ERROR, TAG, "Volume insertion skipped: trigger=$trigger has no UI Looper", null)
                return
            }
            val packages = SystemUiVolumeEntryLayout.resourcePackages(targetContext.packageName)
            val styleHost = findStyleTemplate(root, packages, log)
            val template = resolveDndTemplate(styleHost ?: root, packages, log)
            val density = targetContext.resources.displayMetrics.density
            fun dp(value: Int): Int = (value * density + 0.5f).toInt()
            val dimenWidth = resolveNamedDimenPx(
                targetContext,
                packages,
                SystemUiVolumeEntryLayout.BUTTON_WIDTH_DIMEN_NAMES,
                log,
            )
            val dimenHeight = resolveNamedDimenPx(
                targetContext,
                packages,
                SystemUiVolumeEntryLayout.BUTTON_HEIGHT_DIMEN_NAMES,
                log,
            )
            val metrics = resolveButtonMetrics(template, ::dp, dimenWidth, dimenHeight)
            val gap = resolveOfficialGap(root, anchor, dp(SystemUiVolumeEntryLayout.MARGIN_VERTICAL_DP))
            val placement = resolvePlacement(root, anchor, metrics, gap, log) ?: return
            val dialogBound = resolveDialogBound(root)
            if (!isWithinBound(placement.parent, dialogBound)) {
                failVisible(
                    placement.parent,
                    log,
                    "resolved placement escaped $VOLUME_DIALOG_VIEW_CLASS",
                )
                return
            }
            val existing: View? = findExistingEntry(root)
                ?: placement.parent.findViewWithTag(ENTRY_TAG)
            if (existing != null && existing !is FrameLayout) {
                log(
                    Log.WARN,
                    TAG,
                    "[systemui] $trigger removing stale non-frame entry view=${existing.javaClass.name}@" +
                        Integer.toHexString(System.identityHashCode(existing)),
                    null,
                )
                (existing.parent as? ViewGroup)?.removeView(existing)
            }
            if (isClosing()) return
            val entry = (existing as? FrameLayout) ?: FrameLayout(targetContext)
            try {
                if (!configureEntry(
                        entry,
                        targetContext,
                        template,
                        packages,
                        log,
                        isClosing,
                        openOverlay,
                        officialBlur,
                    )
                ) {
                    return
                }
                val previousParent = entry.parent as? ViewGroup
                val previousIndex = previousParent?.indexOfChild(entry) ?: -1
                previousParent?.removeView(entry)
                val insertIndex =
                    if (previousParent === placement.parent && previousIndex in 0 until placement.index) {
                        placement.index - 1
                    } else {
                        placement.index
                    }
                placement.parent.addView(entry, insertIndex, placement.layoutParams)
                applyInsertVisibility(entry, template.timerLayout, log)
                if (!track(entry, uiLooper)) {
                    cleanup(entry)
                    return
                }
                entry.tag = ENTRY_TAG
                val action = if (existing === entry) "adopted" else "inserted"
                log(
                    Log.INFO,
                    TAG,
                    "[systemui] $trigger $action SoundMan entry view=${describeInserted(entry)} " +
                        "anchor=$anchorName parent=${placement.parent.javaClass.name} index=$insertIndex " +
                        "size=${placement.layoutParams.width}x${placement.layoutParams.height}",
                    null,
                )
            } catch (throwable: Throwable) {
                cleanup(entry)
                log(
                    Log.ERROR,
                    TAG,
                    "$trigger failed to add SoundMan volume entry to ${placement.parent.javaClass.name}",
                    throwable,
                )
            }
        }

        private fun describeInserted(view: View): String =
            "${view.javaClass.name}@${Integer.toHexString(System.identityHashCode(view))}"

        private fun configureEntry(
            entry: FrameLayout,
            targetContext: Context,
            template: DndTemplate,
            packages: List<String>,
            log: (priority: Int, tag: String, message: String, throwable: Throwable?) -> Unit,
            isClosing: () -> Boolean,
            openOverlay: (Context, String, View) -> Unit,
            officialBlur: OfficialRingerBlur?,
        ): Boolean {
            val iconDrawable = resolvePhoneIcon(targetContext, packages, log) ?: return false
            val radiusPx = resolveNamedDimenPx(
                targetContext,
                packages,
                SystemUiVolumeEntryLayout.BUTTON_RADIUS_DIMEN_NAMES,
                log,
            )
            val density = targetContext.resources.displayMetrics.density
            fun dp(value: Int): Int = (value * density + 0.5f).toInt()
            val fallbackWidth = resolveNamedDimenPx(
                targetContext,
                packages,
                SystemUiVolumeEntryLayout.BUTTON_WIDTH_DIMEN_NAMES,
                log,
            ) ?: dp(SystemUiVolumeEntryLayout.BUTTON_SIZE_DP)
            val fallbackHeight = resolveNamedDimenPx(
                targetContext,
                packages,
                SystemUiVolumeEntryLayout.BUTTON_HEIGHT_DIMEN_NAMES,
                log,
            ) ?: dp(SystemUiVolumeEntryLayout.BUTTON_SIZE_DP)
            val iconSizePx = resolveNamedDimenPx(
                targetContext,
                packages,
                SystemUiVolumeEntryLayout.ICON_SIZE_DIMEN_NAMES,
                log,
            )
            val moduleContext = targetContext.createPackageContext(
                MODULE_PACKAGE,
                Context.CONTEXT_IGNORE_SECURITY,
            )
            val contentDescription = moduleContext.getString(R.string.systemui_volume_entry_content_description)
            entry.id = View.NO_ID
            entry.removeAllViews()
            entry.background = null
            entry.elevation = 0f
            entry.isClickable = true
            entry.isFocusable = true
            entry.contentDescription = contentDescription
            entry.tag = ENTRY_TAG
            val liveRadius = radiusPx ?: (fallbackWidth / 2)
            val blurLayer = officialBlur?.createCollapsedBlurLayer(targetContext, liveRadius)
                ?: View(targetContext)
            blurLayer.id = View.NO_ID
            blurLayer.layoutParams = childLayoutParams(template.bgBlur, fallbackWidth, fallbackHeight)
            applyRoundOutline(blurLayer, template.bgBlur, radiusPx)
            val chrome = FrameLayout(targetContext)
            chrome.id = View.NO_ID
            chrome.isActivated = true
            chrome.isSelected = false
            chrome.layoutParams = childLayoutParams(template.standardBtn, fallbackWidth, fallbackHeight)
            applyRoundOutline(chrome, template.standardBtn, radiusPx)
            val blurBackground = resolveNamedDrawable(
                targetContext,
                packages,
                SystemUiVolumeEntryLayout.BLUR_BACKGROUND_RESOURCE_NAMES,
                log,
                "volume entry blur background",
            )
            val buttonBackground = resolveNamedDrawable(
                targetContext,
                packages,
                SystemUiVolumeEntryLayout.BUTTON_BACKGROUND_RESOURCE_NAMES,
                log,
                "volume entry button background",
            )
            val themeBlur = officialBlur?.themeBlurOpened(targetContext)
            val liveApplied = themeBlur != false && officialBlur != null &&
                officialBlur.applyCollapsedChrome(chrome, liveRadius)
            if (themeBlur == true && !liveApplied) {
                log(Log.ERROR, TAG, "Theme live blur is on but official chrome blend failed; skip insertion", null)
                return false
            }
            blurLayer.background = blurBackground
            if (liveApplied) {
                chrome.background = null
                log(Log.INFO, TAG, "Applied official chrome MiBlur; kept ringer blur drawable under it", null)
            } else {
                if (buttonBackground == null && blurBackground == null) {
                    log(Log.ERROR, TAG, "Volume entry official backgrounds missing; skip insertion", null)
                    return false
                }
                chrome.background = buttonBackground
            }
            chrome.addView(createIconView(targetContext, template.icon, iconDrawable, iconSizePx))
            entry.addView(blurLayer)
            entry.addView(chrome)
            entry.setOnClickListener { clickedView ->
                if (isClosing()) return@setOnClickListener
                openOverlay(clickedView.context, "click", clickedView)
            }
            return true
        }

        private fun createIconView(
            context: Context,
            iconTemplate: View?,
            drawable: Drawable,
            iconSizePx: Int?,
        ): ImageView {
            val imageView = ImageView(context)
            imageView.id = View.NO_ID
            imageView.setImageDrawable(drawable)
            if (iconTemplate is ImageView) {
                imageView.scaleType = iconTemplate.scaleType
                imageView.adjustViewBounds = iconTemplate.adjustViewBounds
                imageView.setPadding(
                    iconTemplate.paddingLeft,
                    iconTemplate.paddingTop,
                    iconTemplate.paddingRight,
                    iconTemplate.paddingBottom,
                )
                val sourceParams = iconTemplate.layoutParams
                imageView.layoutParams = if (sourceParams != null) {
                    FrameLayout.LayoutParams(sourceParams.width, sourceParams.height).apply {
                        gravity = Gravity.CENTER
                        if (sourceParams is ViewGroup.MarginLayoutParams) {
                            setMargins(
                                sourceParams.leftMargin,
                                sourceParams.topMargin,
                                sourceParams.rightMargin,
                                sourceParams.bottomMargin,
                            )
                        }
                    }
                } else {
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        Gravity.CENTER,
                    )
                }
            } else {
                imageView.scaleType = ImageView.ScaleType.CENTER_INSIDE
                val size = iconSizePx ?: ViewGroup.LayoutParams.MATCH_PARENT
                imageView.layoutParams = FrameLayout.LayoutParams(size, size, Gravity.CENTER)
            }
            return imageView
        }

        private fun childLayoutParams(
            source: View?,
            fallbackWidth: Int?,
            fallbackHeight: Int?,
        ): FrameLayout.LayoutParams {
            val sourceParams = source?.layoutParams
            val width = when {
                source != null && source.measuredWidth > 0 -> source.measuredWidth
                sourceParams != null && sourceParams.width > 0 -> sourceParams.width
                sourceParams != null && sourceParams.width != 0 -> sourceParams.width
                fallbackWidth != null && fallbackWidth > 0 -> fallbackWidth
                else -> ViewGroup.LayoutParams.WRAP_CONTENT
            }
            val height = when {
                source != null && source.measuredHeight > 0 -> source.measuredHeight
                sourceParams != null && sourceParams.height > 0 -> sourceParams.height
                sourceParams != null && sourceParams.height != 0 -> sourceParams.height
                fallbackHeight != null && fallbackHeight > 0 -> fallbackHeight
                else -> ViewGroup.LayoutParams.WRAP_CONTENT
            }
            return FrameLayout.LayoutParams(width, height, Gravity.CENTER).apply {
                if (sourceParams is ViewGroup.MarginLayoutParams) {
                    setMargins(
                        sourceParams.leftMargin,
                        sourceParams.topMargin,
                        sourceParams.rightMargin,
                        sourceParams.bottomMargin,
                    )
                }
            }
        }

        private fun applyRoundOutline(target: View, template: View?, radiusPx: Int?) {
            target.clipToOutline = true
            val templateProvider = template?.outlineProvider
            if (templateProvider != null) {
                target.outlineProvider = templateProvider
                return
            }
            target.outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    if (view.width <= 0 || view.height <= 0) {
                        outline.setEmpty()
                        return
                    }
                    if (radiusPx != null) {
                        outline.setRoundRect(0, 0, view.width, view.height, radiusPx.toFloat())
                    } else {
                        outline.setOval(0, 0, view.width, view.height)
                    }
                }
            }
        }

        private fun resolvePhoneIcon(
            context: Context,
            packages: List<String>,
            log: (priority: Int, tag: String, message: String, throwable: Throwable?) -> Unit,
        ): Drawable? {
            val drawable = resolveNamedDrawable(
                context,
                packages,
                SystemUiVolumeEntryLayout.ICON_RESOURCE_NAMES,
                log,
                "volume entry icon",
            )
            if (drawable == null) {
                log(
                    Log.ERROR,
                    TAG,
                    "Volume entry icon not found: ${SystemUiVolumeEntryLayout.ICON_RESOURCE_NAMES} in $packages",
                    null,
                )
            }
            return drawable
        }

        private fun resolveNamedDrawable(
            context: Context,
            packages: List<String>,
            names: List<String>,
            log: (priority: Int, tag: String, message: String, throwable: Throwable?) -> Unit,
            purpose: String,
        ): Drawable? {
            names.forEach { name ->
                packages.forEach { packageName ->
                    val id = runCatching {
                        context.resources.getIdentifier(name, "drawable", packageName)
                    }.onFailure {
                        log(Log.ERROR, TAG, "getIdentifier failed name=$name type=drawable package=$packageName", it)
                    }.getOrDefault(0)
                    if (id == 0) return@forEach
                    val drawable = runCatching {
                        context.getDrawable(id)
                    }.onFailure {
                        log(Log.ERROR, TAG, "getDrawable failed name=$name package=$packageName id=$id", it)
                    }.getOrNull()
                    if (drawable != null) {
                        log(Log.INFO, TAG, "Resolved $purpose name=$name package=$packageName", null)
                        return drawable.mutate()
                    }
                }
            }
            return null
        }

        private fun resolveNamedDimenPx(
            context: Context,
            packages: List<String>,
            names: List<String>,
            log: (priority: Int, tag: String, message: String, throwable: Throwable?) -> Unit,
        ): Int? {
            names.forEach { name ->
                packages.forEach { packageName ->
                    val id = runCatching {
                        context.resources.getIdentifier(name, "dimen", packageName)
                    }.onFailure {
                        log(Log.ERROR, TAG, "getIdentifier failed name=$name type=dimen package=$packageName", it)
                    }.getOrDefault(0)
                    if (id == 0) return@forEach
                    val px = runCatching {
                        context.resources.getDimensionPixelSize(id)
                    }.onFailure {
                        log(Log.ERROR, TAG, "getDimensionPixelSize failed name=$name package=$packageName id=$id", it)
                    }.getOrNull()
                    if (px != null) {
                        log(Log.INFO, TAG, "Resolved dimen name=$name package=$packageName px=$px", null)
                        return px
                    }
                }
            }
            return null
        }

        private fun resolveDndTemplate(
            anchor: View,
            packages: List<String>,
            log: (priority: Int, tag: String, message: String, throwable: Throwable?) -> Unit,
        ): DndTemplate {
            val children = LinkedHashMap<String, View>()
            SystemUiVolumeEntryLayout.DND_CHILD_RESOURCE_NAMES.forEach { name ->
                findViewByIdName(anchor, name, packages, log)?.let { children[name] = it }
            }
            return DndTemplate(
                standardBtn = children["miui_standard_btn"],
                bgBlur = children["bg_blur"],
                icon = children["icon"],
                timerLayout = findViewByIdName(
                    anchor,
                    SystemUiVolumeEntryLayout.TIMER_LAYOUT_RESOURCE_NAME,
                    packages,
                    log,
                ),
            )
        }

        private fun findViewByIdName(
            scope: View,
            name: String,
            packages: List<String>,
            log: (priority: Int, tag: String, message: String, throwable: Throwable?) -> Unit,
        ): View? {
            packages.forEach { packageName ->
                val id = runCatching {
                    scope.context.resources.getIdentifier(name, "id", packageName)
                }.onFailure {
                    log(Log.ERROR, TAG, "getIdentifier failed name=$name package=$packageName", it)
                }.getOrDefault(0)
                if (id == 0) return@forEach
                val view = scope.findViewById<View>(id)
                if (view != null) return view
            }
            return null
        }

        private fun applyInsertVisibility(
            entry: View,
            timerLayout: View?,
            log: (priority: Int, tag: String, message: String, throwable: Throwable?) -> Unit,
        ) {
            val expanded = timerLayout != null && timerLayout.visibility == View.VISIBLE
            entry.visibility = SystemUiVolumeEntryLayout.entryVisibility(expanded)
            if (expanded) {
                log(Log.INFO, TAG, "Volume entry hidden because DND timer_layout is already visible", null)
            }
        }

        private fun copyMetrics(
            sizeSource: View,
            marginSource: View,
            dp: (Int) -> Int,
            dimenWidth: Int?,
            dimenHeight: Int?,
        ): CopiedMetrics {
            val spec = SystemUiVolumeEntryLayout.circularButtonSpec()
            val fallback = dp(spec.sizeDp)
            val fallbackWidth = dimenWidth ?: fallback
            val fallbackHeight = dimenHeight ?: fallback
            val fallbackMargin = dp(spec.marginVerticalDp)
            val sizeLp = sizeSource.layoutParams
            val width = when {
                sizeSource.measuredWidth > 0 -> sizeSource.measuredWidth
                sizeLp != null && sizeLp.width > 0 -> sizeLp.width
                else -> fallbackWidth
            }
            val height = when {
                sizeSource.measuredHeight > 0 -> sizeSource.measuredHeight
                sizeLp != null && sizeLp.height > 0 -> sizeLp.height
                else -> fallbackHeight
            }
            val marginLp = (marginSource.layoutParams as? ViewGroup.MarginLayoutParams)
                ?: (sizeLp as? ViewGroup.MarginLayoutParams)
            val gravitySource = sizeLp ?: marginSource.layoutParams
            val gravity = when (gravitySource) {
                is LinearLayout.LayoutParams -> gravitySource.gravity
                is FrameLayout.LayoutParams -> gravitySource.gravity
                else -> Gravity.CENTER_HORIZONTAL
            }.let { resolved ->
                if (resolved == Gravity.NO_GRAVITY) Gravity.CENTER_HORIZONTAL else resolved
            }
            return CopiedMetrics(
                width = width,
                height = height,
                leftMargin = marginLp?.leftMargin ?: 0,
                topMargin = marginLp?.topMargin ?: fallbackMargin,
                rightMargin = marginLp?.rightMargin ?: 0,
                bottomMargin = marginLp?.bottomMargin ?: fallbackMargin,
                gravity = gravity,
            )
        }

        private fun resolvePlacement(
            root: View,
            anchor: View,
            metrics: CopiedMetrics,
            gap: Int,
            log: (priority: Int, tag: String, message: String, throwable: Throwable?) -> Unit,
        ): EntryPlacement? {
            val parent = anchor.parent as? ViewGroup
            if (parent == null) {
                log(Log.ERROR, TAG, "Volume column parent is not a ViewGroup: ${anchor.javaClass.name}", null)
                return null
            }
            val dialogBound = resolveDialogBound(root)
            if (!isWithinBound(parent, dialogBound)) {
                return failVisible(
                    parent,
                    log,
                    "volume column parent is outside $VOLUME_DIALOG_VIEW_CLASS",
                )
            }
            val entryWidth = alignEntryWidth(anchor)
            return when (parent) {
                is LinearLayout -> when (parent.orientation) {
                    LinearLayout.VERTICAL -> verticalPlacement(parent, anchor, metrics, entryWidth, gap)
                    LinearLayout.HORIZONTAL -> outerVerticalPlacement(
                        root,
                        parent,
                        anchor,
                        metrics,
                        entryWidth,
                        gap,
                        log,
                        "horizontal LinearLayout",
                    )
                    else -> failVisible(parent, log, "LinearLayout has unsupported orientation")
                }
                is FrameLayout -> framePlacement(parent, anchor, metrics, entryWidth, gap)
                    ?: outerVerticalPlacement(
                        root,
                        parent,
                        anchor,
                        metrics,
                        entryWidth,
                        gap,
                        log,
                        "FrameLayout cannot place entry above volume column",
                    )
                else -> failVisible(parent, log, "unsupported volume column parent")
            }
        }

        private fun verticalPlacement(
            parent: LinearLayout,
            insertBefore: View,
            metrics: CopiedMetrics,
            entryWidth: Int,
            gap: Int,
        ): EntryPlacement {
            val params = LinearLayout.LayoutParams(entryWidth, metrics.height).apply {
                weight = 0f
                gravity = volumeRowGravity(insertBefore)
                setMargins(0, 0, 0, gap)
            }
            return EntryPlacement(parent, parent.indexOfChild(insertBefore), params)
        }

        private fun outerVerticalPlacement(
            root: View,
            originalParent: ViewGroup,
            volumeAnchor: View,
            metrics: CopiedMetrics,
            entryWidth: Int,
            gap: Int,
            log: (priority: Int, tag: String, message: String, throwable: Throwable?) -> Unit,
            reason: String,
        ): EntryPlacement? {
            val dialogBound = resolveDialogBound(root)
            var row: View = originalParent
            var ancestor = originalParent.parent
            while (ancestor is ViewGroup && isWithinBound(ancestor, dialogBound)) {
                if (ancestor is LinearLayout && ancestor.orientation == LinearLayout.VERTICAL) {
                    return verticalPlacement(ancestor, row, metrics, alignEntryWidth(row), gap)
                }
                if (ancestor === dialogBound) break
                row = ancestor
                ancestor = ancestor.parent
            }
            return failVisible(
                originalParent,
                log,
                "$reason has no vertical container within $VOLUME_DIALOG_VIEW_CLASS for ${volumeAnchor.javaClass.name}",
            )
        }

        private fun isWithinRoot(view: View, root: View): Boolean = isWithinBound(view, root)

        private fun isWithinBound(view: View, bound: View): Boolean {
            var current: View? = view
            while (current != null) {
                if (current === bound) return true
                current = current.parent as? View
            }
            return false
        }

        private fun resolveDialogBound(ringerRoot: View): View {
            var current: View = ringerRoot
            while (true) {
                if (current.javaClass.name == VOLUME_DIALOG_VIEW_CLASS) return current
                current = current.parent as? View ?: return current
            }
        }

        private fun findExistingEntry(root: View): View? {
            resolveDialogBound(root).findViewWithTag<View>(ENTRY_TAG)?.let { return it }
            return null
        }

        private fun findStyleTemplate(
            ringerRoot: View,
            packages: List<String>,
            log: (priority: Int, tag: String, message: String, throwable: Throwable?) -> Unit,
        ): View? {
            SystemUiVolumeEntryLayout.STYLE_TEMPLATE_RESOURCE_NAMES.forEach { name ->
                val view = findViewByIdName(ringerRoot, name, packages, log)
                if (view != null) {
                    log(Log.INFO, TAG, "Found style template id=$name view=${view.javaClass.name}", null)
                    return view
                }
            }
            log(Log.WARN, TAG, "Style template dnd_layout/ringer_layout not found; using official dimen sizes", null)
            return null
        }

        private fun resolveButtonMetrics(
            template: DndTemplate,
            dp: (Int) -> Int,
            dimenWidth: Int?,
            dimenHeight: Int?,
        ): CopiedMetrics {
            val sizeSource = template.standardBtn ?: template.bgBlur
            val fallback = dp(SystemUiVolumeEntryLayout.BUTTON_SIZE_DP)
            if (sizeSource != null) {
                return copyMetrics(sizeSource, sizeSource, dp, dimenWidth, dimenHeight).copy(
                    leftMargin = 0,
                    topMargin = 0,
                    rightMargin = 0,
                    bottomMargin = 0,
                    gravity = Gravity.CENTER_HORIZONTAL,
                )
            }
            return CopiedMetrics(
                width = dimenWidth ?: fallback,
                height = dimenHeight ?: fallback,
                leftMargin = 0,
                topMargin = 0,
                rightMargin = 0,
                bottomMargin = 0,
                gravity = Gravity.CENTER_HORIZONTAL,
            )
        }

        private fun resolveOfficialGap(ringerRoot: View, volumeAnchor: View, fallbackPx: Int): Int {
            val ringerMargin = (ringerRoot.layoutParams as? ViewGroup.MarginLayoutParams)?.topMargin ?: 0
            if (ringerMargin > 0) return ringerMargin
            val volumeMargin = (volumeAnchor.layoutParams as? ViewGroup.MarginLayoutParams)?.bottomMargin ?: 0
            if (volumeMargin > 0) return volumeMargin
            if (ringerRoot.isLaidOut && volumeAnchor.isLaidOut) {
                if (ringerRoot.parent === volumeAnchor.parent) {
                    return kotlin.math.abs(ringerRoot.top - volumeAnchor.bottom)
                }
                if (ringerRoot.isAttachedToWindow && volumeAnchor.isAttachedToWindow) {
                    val ringerLoc = IntArray(2)
                    val volumeLoc = IntArray(2)
                    ringerRoot.getLocationOnScreen(ringerLoc)
                    volumeAnchor.getLocationOnScreen(volumeLoc)
                    return kotlin.math.abs(ringerLoc[1] - (volumeLoc[1] + volumeAnchor.height))
                }
            }
            return fallbackPx
        }

        private fun alignEntryWidth(volumeAnchor: View): Int {
            val layoutParams = volumeAnchor.layoutParams
            return when {
                volumeAnchor.measuredWidth > 0 -> volumeAnchor.measuredWidth
                layoutParams != null && layoutParams.width > 0 -> layoutParams.width
                layoutParams != null && layoutParams.width == ViewGroup.LayoutParams.MATCH_PARENT ->
                    ViewGroup.LayoutParams.MATCH_PARENT
                else -> ViewGroup.LayoutParams.WRAP_CONTENT
            }
        }

        private fun volumeRowGravity(volumeAnchor: View): Int {
            val layoutParams = volumeAnchor.layoutParams
            val gravity = when (layoutParams) {
                is LinearLayout.LayoutParams -> layoutParams.gravity
                is FrameLayout.LayoutParams -> layoutParams.gravity
                else -> Gravity.CENTER_HORIZONTAL
            }
            return if (gravity == Gravity.NO_GRAVITY) Gravity.CENTER_HORIZONTAL else gravity
        }

        private fun framePlacement(
            parent: FrameLayout,
            anchor: View,
            metrics: CopiedMetrics,
            entryWidth: Int,
            gap: Int,
        ): EntryPlacement? {
            val topMargin = anchor.top - gap - metrics.height
            if (topMargin < 0) return null
            val params = FrameLayout.LayoutParams(entryWidth, metrics.height).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                this.topMargin = topMargin
            }
            return EntryPlacement(parent, parent.indexOfChild(anchor), params)
        }

        private fun failVisible(
            parent: ViewGroup,
            log: (priority: Int, tag: String, message: String, throwable: Throwable?) -> Unit,
            reason: String,
        ): EntryPlacement? {
            log(Log.ERROR, TAG, "SoundMan volume entry not inserted: $reason; parent=${parent.javaClass.name}", null)
            return null
        }

        private data class EntryPlacement(
            val parent: ViewGroup,
            val index: Int,
            val layoutParams: ViewGroup.LayoutParams,
        )

        private data class DndTemplate(
            val standardBtn: View?,
            val bgBlur: View?,
            val icon: View?,
            val timerLayout: View?,
        )

        private data class CopiedMetrics(
            val width: Int,
            val height: Int,
            val leftMargin: Int,
            val topMargin: Int,
            val rightMargin: Int,
            val bottomMargin: Int,
            val gravity: Int,
        )
    }
}
