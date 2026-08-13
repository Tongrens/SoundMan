package hk.uwu.soundman.ui

/**
 * 面板页切换的稳定键。
 *
 * 动机：`AdjustableApp` 每次快照都会换新的 Drawable，不能当 AnimatedContent
 * 的 targetState，否则切设备后页面会整页重播动画闪一下。
 */
object PanelPageKey {
    /**
     * 用包名做设备页键；应用不在当前可见列表里则回到音量列表。
     *
     * @param selectedPackage 用户打开的设备页包名
     * @param visiblePackageNames 当前应展示的播放应用包名
     */
    fun of(selectedPackage: String?, visiblePackageNames: Collection<String>): String? {
        if (selectedPackage.isNullOrBlank()) return null
        return selectedPackage.takeIf(visiblePackageNames::contains)
    }
}
