package hk.uwu.soundman.overlay

import kotlin.math.roundToInt

/**
 * 浮层窗口遮罩与面板揭示进度同步。
 *
 * 动机：WindowManager 的 dim / blur 如果一直停在全强度，面板滑走后背景还会多停一会儿。
 */
object OverlayWindowReveal {
    const val DIM_AMOUNT = 0.45f
    const val BLUR_RADIUS_PX = 80

    /**
     * 把 0..1 揭示进度映射成窗口遮罩。
     *
     * @param reveal 0 全关，1 全开
     */
    fun chrome(reveal: Float): OverlayWindowChrome {
        require(reveal in 0f..1f) { "reveal must be in 0..1" }
        val blurRadiusPx = (BLUR_RADIUS_PX * reveal).roundToInt()
        return OverlayWindowChrome(
            dimAmount = DIM_AMOUNT * reveal,
            blurRadiusPx = blurRadiusPx,
            blurEnabled = blurRadiusPx > 0,
        )
    }
}

/**
 * 某一帧窗口应使用的 dim / blur。
 *
 * @param dimAmount 窗口背后变暗强度
 * @param blurRadiusPx 背后模糊半径像素
 * @param blurEnabled 半径为 0 时关掉 FLAG_BLUR_BEHIND，避免残影
 */
data class OverlayWindowChrome(
    val dimAmount: Float,
    val blurRadiusPx: Int,
    val blurEnabled: Boolean,
)
