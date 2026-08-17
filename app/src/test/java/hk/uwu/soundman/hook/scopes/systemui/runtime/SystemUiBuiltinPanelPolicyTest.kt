package hk.uwu.soundman.hook.scopes.systemui.runtime

import hk.uwu.soundman.data.PanelPlaybackRow
import hk.uwu.soundman.data.PanelPlaybackSnapshot
import hk.uwu.soundman.data.PanelPlaybackStatus
import hk.uwu.soundman.model.AudioDeviceIdentity
import hk.uwu.soundman.model.AudioOutputDevice
import hk.uwu.soundman.model.OutputDeviceType
import hk.uwu.soundman.model.OutputTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemUiBuiltinPanelPolicyTest {
    @Test
    fun disabledUsesOverlayWithoutMountAttempt() {
        var mounted = false
        var overlayOpened = false

        val path = SystemUiBuiltinPanelPolicy.open(
            builtinEnabled = false,
            mountBuiltin = { mounted = true; true },
            openOverlay = { overlayOpened = true },
        )

        assertEquals(SystemUiVolumePanelPath.OVERLAY, path)
        assertFalse(mounted)
        assertTrue(overlayOpened)
    }

    @Test
    fun enabledUsesBuiltinWhenMountSucceeds() {
        var overlayOpened = false

        val path = SystemUiBuiltinPanelPolicy.open(
            builtinEnabled = true,
            mountBuiltin = { true },
            openOverlay = { overlayOpened = true },
        )

        assertEquals(SystemUiVolumePanelPath.BUILTIN, path)
        assertFalse(overlayOpened)
    }

    @Test
    fun enabledFallsBackToOverlayWhenMountFails() {
        var overlayOpened = false

        val path = SystemUiBuiltinPanelPolicy.open(
            builtinEnabled = true,
            mountBuiltin = { false },
            openOverlay = { overlayOpened = true },
        )

        assertEquals(SystemUiVolumePanelPath.OVERLAY, path)
        assertTrue(overlayOpened)
    }

    @Test
    fun appRowsDeduplicateUidAndSortLabels() {
        val rows = listOf(
            SystemUiBuiltinAppRowState("z.app", "Zulu", 20, 80),
            SystemUiBuiltinAppRowState("a.app", "alpha", 10, 40),
            SystemUiBuiltinAppRowState("duplicate.app", "Duplicate", 20, 10),
        )

        val sorted = SystemUiBuiltinPanelState.sorted(rows)

        assertEquals(listOf(10, 20), sorted.map { it.uid })
        assertEquals(listOf(40, 80), sorted.map { it.volumePercent })
    }

    @Test(expected = IllegalArgumentException::class)
    fun appRowRejectsOutOfRangeVolume() {
        SystemUiBuiltinAppRowState("bad.app", "Bad", 1, 101)
    }

    @Test
    fun independentPanelMorphAnchorsExpandedRectAtNearestHorizontalEdge() {
        val right = SystemUiIndependentPanelPolicy.animationSpec(
            folded = SystemUiPanelRect(840, 40, 960, 340),
            expandedWidth = 360,
            expandedHeight = 600,
            parentWidth = 1080,
            parentHeight = 2400,
        )
        val left = SystemUiIndependentPanelPolicy.animationSpec(
            folded = SystemUiPanelRect(120, 40, 300, 340),
            expandedWidth = 360,
            expandedHeight = 600,
            parentWidth = 1080,
            parentHeight = 2400,
        )

        // 水平按最近边缘锚定；垂直锚定折叠入口顶部，保证卡片与音量条平行。
        assertEquals(SystemUiPanelRect(600, 40, 960, 640), right.expanded)
        assertEquals(SystemUiPanelRect(120, 40, 480, 640), left.expanded)
    }

    @Test
    fun independentPanelExpandedRectAnchorsFoldedTopRegardlessOfParentHeight() {
        val highParent = SystemUiIndependentPanelPolicy.animationSpec(
            folded = SystemUiPanelRect(840, 60, 960, 360),
            expandedWidth = 360,
            expandedHeight = 436,
            parentWidth = 1080,
            parentHeight = 2400,
        )
        val lowParent = SystemUiIndependentPanelPolicy.animationSpec(
            folded = SystemUiPanelRect(840, 60, 960, 360),
            expandedWidth = 360,
            expandedHeight = 436,
            parentWidth = 1080,
            parentHeight = 1600,
        )

        // 展开面板顶部始终锚定折叠入口顶部（与音量条平行），不做屏幕级居中。
        assertEquals(60, highParent.expanded.top)
        assertEquals(60, lowParent.expanded.top)
    }

    @Test
    fun independentPanelMorphInterpolatesPositionAndSizeTogether() {
        val spec = SystemUiIndependentPanelAnimationSpec(
            folded = SystemUiPanelRect(600, 40, 960, 340),
            expanded = SystemUiPanelRect(300, 100, 1080, 700),
        )

        assertEquals(
            SystemUiPanelRect(450, 70, 1020, 520),
            SystemUiIndependentPanelPolicy.interpolateRect(spec, 0.5f),
        )
    }

    @Test
    fun independentPanelDismissTransformSlidesOutAndKeepsOfficialScaleProfile() {
        val right = SystemUiIndependentPanelPolicy.dismissTransform(
            panel = SystemUiPanelRect(840, 100, 960, 400),
            rootWidth = 1080,
            fraction = 1f,
        )
        val left = SystemUiIndependentPanelPolicy.dismissTransform(
            panel = SystemUiPanelRect(120, 100, 300, 400),
            rootWidth = 1080,
            fraction = 1f,
        )
        val start = SystemUiIndependentPanelPolicy.dismissTransform(
            panel = SystemUiPanelRect(840, 100, 960, 400),
            rootWidth = 1080,
            fraction = 0f,
        )

        assertEquals(1_200f, right.translationX, 0.0001f)
        assertEquals(-360f, left.translationX, 0.0001f)
        assertEquals(100f, right.translationY, 0.0001f)
        assertEquals(0.8f, right.scale, 0.0001f)
        assertEquals(0f, start.translationX, 0.0001f)
        assertEquals(0f, start.translationY, 0.0001f)
        assertEquals(1f, start.scale, 0.0001f)
    }

    @Test
    fun singleActionColumnWidthDoesNotReserveRemovedSecondButton() {
        assertEquals(
            64,
            SystemUiIndependentPanelPolicy.singleActionColumnWidth(
                officialWidth = 64,
                actionSize = 40,
            ),
        )
        assertEquals(
            48,
            SystemUiIndependentPanelPolicy.singleActionColumnWidth(
                officialWidth = 32,
                actionSize = 48,
            ),
        )
    }

    @Test
    fun volumeColumnsUseOfficialLayerNodesAndLocalProgress() {
        assertEquals(0.3f, SystemUiIndependentPanelPolicy.volumeColumnNode(0), 0.0001f)
        assertEquals(0.5f, SystemUiIndependentPanelPolicy.volumeColumnNode(1), 0.0001f)
        assertEquals(0.6f, SystemUiIndependentPanelPolicy.volumeColumnNode(2), 0.0001f)
        assertEquals(0.7f, SystemUiIndependentPanelPolicy.volumeColumnNode(3), 0.0001f)

        val hidden = SystemUiIndependentPanelPolicy.volumeColumnLayerState(0, 0.2f)
        val visible = SystemUiIndependentPanelPolicy.volumeColumnLayerState(0, 1f)
        assertEquals(0f, hidden.alpha, 0.0001f)
        assertEquals(0.6f, hidden.scale, 0.0001f)
        assertEquals(1f, hidden.translationZFraction, 0.0001f)
        assertEquals(1f, visible.alpha, 0.0001f)
        assertEquals(1f, visible.scale, 0.0001f)
        assertEquals(0f, visible.translationZFraction, 0.0001f)
    }

    @Test
    fun touchInsetsListenerDecisionIsIdempotentAcrossMountAndClose() {
        assertEquals(
            SystemUiInsetsListenerAction.REMOVE,
            SystemUiIndependentPanelPolicy.insetsListenerAction(
                listenerPaused = false,
                panelActive = true
            ),
        )
        assertEquals(
            SystemUiInsetsListenerAction.NONE,
            SystemUiIndependentPanelPolicy.insetsListenerAction(
                listenerPaused = true,
                panelActive = true
            ),
        )
        assertEquals(
            SystemUiInsetsListenerAction.ADD,
            SystemUiIndependentPanelPolicy.insetsListenerAction(
                listenerPaused = true,
                panelActive = false
            ),
        )
        assertEquals(
            SystemUiInsetsListenerAction.NONE,
            SystemUiIndependentPanelPolicy.insetsListenerAction(
                listenerPaused = false,
                panelActive = false
            ),
        )
    }

    @Test
    fun snapshotFingerprintIsStableAcrossProviderRowOrder() {
        val first = PanelPlaybackSnapshot(
            PanelPlaybackStatus.AVAILABLE,
            listOf(
                PanelPlaybackRow("b.app", 20, 80, OutputTarget.FollowSystem),
                PanelPlaybackRow("a.app", 10, 40, OutputTarget.FollowSystem),
            ),
            emptyList(),
        )
        val second = first.copy(rows = first.rows.reversed())

        assertEquals(
            SystemUiIndependentPanelPolicy.fingerprint(first),
            SystemUiIndependentPanelPolicy.fingerprint(second),
        )
    }

    @Test
    fun snapshotFingerprintChangesWhenRouteOrDevicesChange() {
        val identity = AudioDeviceIdentity(128, "usb:1")
        val device = AudioOutputDevice(OutputDeviceType.USB, listOf(identity), "USB DAC")
        val base = PanelPlaybackSnapshot(
            PanelPlaybackStatus.AVAILABLE,
            listOf(PanelPlaybackRow("a.app", 10, 40, OutputTarget.FollowSystem)),
            emptyList(),
        )
        val routed = base.copy(
            rows = listOf(PanelPlaybackRow("a.app", 10, 40, device.target)),
            devices = listOf(device),
        )

        assertNotEquals(
            SystemUiIndependentPanelPolicy.fingerprint(base),
            SystemUiIndependentPanelPolicy.fingerprint(routed),
        )
    }

    @Test
    fun fakeStreamsAreStableUniqueAndOutsidePlatformRange() {
        val first = SystemUiFakeStreamAllocator.allocate(listOf("b.app", "a.app", "b.app"))
        val second = SystemUiFakeStreamAllocator.allocate(listOf("a.app", "b.app"))

        assertEquals(first, second)
        assertEquals(2, first.values.toSet().size)
        assertTrue(first.values.all { it >= 10_000 })
    }

    @Test
    fun officialSliderProgressMapsWholeRange() {
        assertEquals(0, SystemUiOfficialSliderProgress.fromPercent(0))
        assertEquals(500, SystemUiOfficialSliderProgress.fromPercent(50))
        assertEquals(1_000, SystemUiOfficialSliderProgress.fromPercent(100))
        assertEquals(0, SystemUiOfficialSliderProgress.toPercent(0))
        assertEquals(50, SystemUiOfficialSliderProgress.toPercent(500))
        assertEquals(100, SystemUiOfficialSliderProgress.toPercent(1_000))
    }

    @Test
    fun compactLayoutWrapsVisibleColumnsAndCapsAtWindowMargins() {
        val twoColumns = SystemUiIndependentPanelPolicy.compactLayout(
            appCount = 2,
            availableWidth = 1_080,
            availableHeight = 2_400,
            columnWidth = 180,
            columnHeight = 720,
            navigationWidth = 96,
            horizontalPadding = 24,
            verticalPadding = 24,
            columnSpacing = 12,
            headerHeight = 108,
            edgeMargin = 36,
            emptyContentWidth = 360,
        )
        val manyColumns = SystemUiIndependentPanelPolicy.compactLayout(
            appCount = 20,
            availableWidth = 1_080,
            availableHeight = 2_400,
            columnWidth = 180,
            columnHeight = 720,
            navigationWidth = 96,
            horizontalPadding = 24,
            verticalPadding = 24,
            columnSpacing = 12,
            headerHeight = 108,
            edgeMargin = 36,
            emptyContentWidth = 360,
        )

        assertEquals(516, twoColumns.width)
        assertEquals(876, twoColumns.height)
        assertEquals(2, twoColumns.visibleColumns)
        assertFalse(twoColumns.scrollable)
        assertEquals(1_008, manyColumns.width)
        assertEquals(4, manyColumns.visibleColumns)
        assertTrue(manyColumns.scrollable)
    }

    @Test
    fun fullWindowHostDistinguishesPanelFromOutsideBlankArea() {
        val panel = SystemUiPanelRect(720, 120, 1_020, 920)

        assertEquals(
            SystemUiPanelHit.INSIDE,
            SystemUiIndependentPanelPolicy.hitTest(panel, 800, 300)
        )
        assertEquals(
            SystemUiPanelHit.OUTSIDE,
            SystemUiIndependentPanelPolicy.hitTest(panel, 300, 300)
        )
        assertEquals(
            SystemUiPanelHit.OUTSIDE,
            SystemUiIndependentPanelPolicy.hitTest(panel, 1_020, 300)
        )
    }

    @Test
    fun sliderBottomIconMoreAndDeviceUseDisjointVerticalSlots() {
        val regions = SystemUiIndependentPanelPolicy.columnRegions(
            columnWidth = 192,
            sliderWidth = 156,
            sliderHeight = 720,
            sizes = SystemUiColumnPixelSizes(
                actionSize = 80,
                actionSpacing = 16,
                iconSize = 64,
                iconSlotHeight = 96,
                actionSlotHeight = 104,
                minimumColumnWidth = 144,
            ),
        )

        assertFalse(regions.hasOverlap())
        // 参考官方音量列：更多/设备按钮与应用图标作为音量条内部的内嵌覆盖层——
        // slider 占满整列（top=0），更多/设备按钮在 slider 顶部内侧，应用图标在 slider 底部内侧。
        assertEquals(0, regions.slider.top)
        assertEquals(720, regions.slider.bottom)
        assertTrue(regions.more.top >= 0 && regions.more.bottom <= regions.slider.bottom)
        assertTrue(regions.device.top >= 0 && regions.device.bottom <= regions.slider.bottom)
        assertTrue(regions.bottomIcon.top >= regions.slider.top && regions.bottomIcon.bottom <= regions.slider.bottom)
        assertTrue(regions.bottomIcon.top >= regions.more.bottom)
        assertFalse(regions.more.intersects(regions.device))
    }

    @Test
    fun outsideTapIsTerminalWithoutFallbackAndReadiesOfficialNextShow() {
        val outside = SystemUiIndependentPanelPolicy.closeState("outside touch")
        assertTrue(outside.terminal)
        assertFalse(outside.reopenFallback)
        assertTrue(outside.dismissOfficialSession)

        val failure = SystemUiIndependentPanelPolicy.closeState("panel bridge failure")
        assertTrue(failure.terminal)
        assertTrue(failure.reopenFallback)
        assertTrue(failure.dismissOfficialSession)
    }

    @Test
    fun independentBackNavigatesInsideStackBeforeClosingOverview() {
        assertEquals(
            SystemUiIndependentBackAction.SHOW_OVERVIEW,
            SystemUiIndependentPanelPolicy.pageBackAction(inDetails = true),
        )
        assertEquals(
            SystemUiIndependentBackAction.CLOSE_PANEL,
            SystemUiIndependentPanelPolicy.pageBackAction(inDetails = false),
        )
    }

    @Test
    fun sliderMovesStayLocalAndStopCommitsFinalValue() {
        assertEquals(
            SystemUiSliderCommitAction.LOCAL_FRAME_ONLY,
            SystemUiIndependentPanelPolicy.sliderCommitAction(
                tracking = true,
                stopTracking = false
            ),
        )
        assertEquals(
            SystemUiSliderCommitAction.COMMIT_FINAL,
            SystemUiIndependentPanelPolicy.sliderCommitAction(tracking = true, stopTracking = true),
        )
        assertEquals(
            SystemUiSliderCommitAction.NONE,
            SystemUiIndependentPanelPolicy.sliderCommitAction(
                tracking = false,
                stopTracking = false
            ),
        )
    }

    @Test
    fun realDensitiesUseOneRoundedPixelModelForLayoutAndPolicy() {
        listOf(1.0f, 1.125f, 1.33125f, 2.625f, 3.0f).forEach { density ->
            val sizes = SystemUiColumnPixelSizes.fromDensity(density)
            val officialWidth = (72 * density + 0.5f).toInt()
            val wrapperWidth = sizes.wrapperWidth(officialWidth)
            val regions = SystemUiIndependentPanelPolicy.columnRegions(
                columnWidth = wrapperWidth,
                sliderWidth = officialWidth,
                sliderHeight = (240 * density + 0.5f).toInt(),
                sizes = sizes,
            )

            assertEquals(sizes.actionContentWidth, regions.device.right - regions.more.left)
            assertTrue(sizes.actionContentWidth <= wrapperWidth)
            assertFalse(regions.hasOverlap())
        }
    }

    @Test
    fun drawablePixelVisibilityRejectsTransparentRasterAndAcceptsAnyVisiblePixel() {
        assertFalse(SystemUiDrawablePixelVisibility.hasVisiblePixel(2, 2, IntArray(4)))
        assertTrue(
            SystemUiDrawablePixelVisibility.hasVisiblePixel(
                2,
                2,
                intArrayOf(0, 0, 0x01000000, 0),
            ),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun drawablePixelVisibilityRejectsMismatchedRasterBuffer() {
        SystemUiDrawablePixelVisibility.hasVisiblePixel(2, 2, IntArray(3))
    }

    @Test
    fun dismissDecisionUsesBothOfficialEntriesBeforeHookFallback() {
        assertEquals(
            listOf(
                SystemUiOfficialDismissEntry.VIEW_CONTROLLER_CALLBACK,
                SystemUiOfficialDismissEntry.DIALOG_EVENT_LISTENER,
                SystemUiOfficialDismissEntry.HOOK_CONTROLLER,
            ),
            SystemUiIndependentPanelPolicy.officialDismissOrder(
                hasViewControllerCallback = true,
                hasDialogEventListener = true,
                hasHookController = true,
            ),
        )
        assertEquals(
            listOf(SystemUiOfficialDismissEntry.HOOK_CONTROLLER),
            SystemUiIndependentPanelPolicy.officialDismissOrder(
                hasViewControllerCallback = false,
                hasDialogEventListener = false,
                hasHookController = true,
            ),
        )
        assertTrue(
            SystemUiIndependentPanelPolicy.officialDismissOrder(
                hasViewControllerCallback = false,
                hasDialogEventListener = false,
                hasHookController = false,
            ).isEmpty(),
        )
    }

    @Test
    fun officialDismissCompletionRunsOnlyForCurrentGenerationAndOnlyOnce() {
        val gate = SystemUiOfficialDismissCompletionGate()

        assertTrue(gate.begin(7L))
        assertFalse(gate.begin(8L))
        assertFalse(gate.complete(6L, 7L))
        assertFalse(gate.complete(7L, 8L))
        assertTrue(gate.complete(7L, 7L))
        assertFalse(gate.complete(7L, 7L))
    }

    @Test
    fun officialDismissCompletesWhenOriginalDialogParentBecomesInvisible() {
        assertEquals(
            SystemUiOfficialDismissCompletionAction.COMPLETE,
            SystemUiIndependentPanelPolicy.officialDismissCompletionAction(
                dialogParentVisible = false,
                elapsedMillis = 16L,
                timeoutMillis = 1_000L,
            ),
        )
        assertEquals(
            SystemUiOfficialDismissCompletionAction.WAIT,
            SystemUiIndependentPanelPolicy.officialDismissCompletionAction(
                dialogParentVisible = true,
                elapsedMillis = 999L,
                timeoutMillis = 1_000L,
            ),
        )
        assertEquals(
            SystemUiOfficialDismissCompletionAction.FORCE_COMPLETE,
            SystemUiIndependentPanelPolicy.officialDismissCompletionAction(
                dialogParentVisible = true,
                elapsedMillis = 1_000L,
                timeoutMillis = 1_000L,
            ),
        )
    }

    @Test
    fun staleCompletionFromPreviousSessionCannotFinalizeNewGeneration() {
        val oldSession = SystemUiOfficialDismissCompletionGate()
        val newSession = SystemUiOfficialDismissCompletionGate()

        assertTrue(oldSession.begin(11L))
        assertTrue(newSession.begin(12L))
        assertFalse(oldSession.complete(11L, 12L))
        assertTrue(newSession.complete(12L, 12L))
    }

    @Test
    fun dismissSequenceContinuesAfterFailuresAndStopsAtFirstSuccess() {
        val attempts = ArrayList<SystemUiOfficialDismissEntry>()
        val selected = SystemUiOfficialDismissSequence.firstSuccessful(
            listOf(
                SystemUiOfficialDismissEntry.VIEW_CONTROLLER_CALLBACK,
                SystemUiOfficialDismissEntry.DIALOG_EVENT_LISTENER,
                SystemUiOfficialDismissEntry.HOOK_CONTROLLER,
            ),
        ) { entry ->
            attempts += entry
            entry == SystemUiOfficialDismissEntry.DIALOG_EVENT_LISTENER
        }

        assertEquals(SystemUiOfficialDismissEntry.DIALOG_EVENT_LISTENER, selected)
        assertEquals(
            listOf(
                SystemUiOfficialDismissEntry.VIEW_CONTROLLER_CALLBACK,
                SystemUiOfficialDismissEntry.DIALOG_EVENT_LISTENER,
            ),
            attempts,
        )
        assertEquals(
            null,
            SystemUiOfficialDismissSequence.firstSuccessful(
                listOf(SystemUiOfficialDismissEntry.HOOK_CONTROLLER),
            ) { false },
        )
    }

    @Test
    fun sliderDragMovesStayLocalAndStopDispatchesBackendExactlyOnce() {
        val session = SystemUiSliderDragSession()
        var backendDispatches = 0
        var committedProgress = -1

        session.start()
        repeat(12) {
            assertEquals(SystemUiSliderCommitAction.LOCAL_FRAME_ONLY, session.move())
            assertEquals(0, backendDispatches)
        }
        assertEquals(
            SystemUiSliderCommitAction.COMMIT_FINAL,
            session.stop(730) { progress ->
                backendDispatches += 1
                committedProgress = progress
            },
        )
        assertEquals(
            SystemUiSliderCommitAction.NONE,
            session.stop(900) { backendDispatches += 1 },
        )
        assertEquals(1, backendDispatches)
        assertEquals(730, committedProgress)
    }

    @Test
    fun iconFallbackPrefersCurrentUserThenLauncherProviderAndDefault() {
        assertEquals(
            SystemUiAppIconSource.SYSTEM_UI_PACKAGE,
            SystemUiAppIconPolicy.choose(
                packageDrawableAvailable = true,
                launcherDrawableAvailable = true,
                providerPayloadAvailable = true,
            ),
        )
        assertEquals(
            SystemUiAppIconSource.LAUNCHER_APPS,
            SystemUiAppIconPolicy.choose(
                packageDrawableAvailable = false,
                launcherDrawableAvailable = true,
                providerPayloadAvailable = true,
            ),
        )
        assertEquals(
            SystemUiAppIconSource.PROVIDER_PAYLOAD,
            SystemUiAppIconPolicy.choose(
                packageDrawableAvailable = false,
                launcherDrawableAvailable = false,
                providerPayloadAvailable = true,
            ),
        )
        assertEquals(
            SystemUiAppIconSource.DEFAULT_ICON,
            SystemUiAppIconPolicy.choose(
                packageDrawableAvailable = false,
                launcherDrawableAvailable = false,
                providerPayloadAvailable = false,
            ),
        )
    }
}
