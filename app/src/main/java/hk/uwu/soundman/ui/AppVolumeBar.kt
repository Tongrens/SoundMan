package hk.uwu.soundman.ui

import android.graphics.drawable.Drawable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import kotlin.math.hypot
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.basic.Icon

/**
 * 横向厚胶囊音量栏的命中与取值。
 *
 * 动机：形状跟系统音量栏同一套胶囊，只是横放。三点在右侧：点按打开更多，滑动仍调音量。
 */
object AppVolumeBarHit {
    const val MORE_HIT_FRACTION = 0.16f

    /**
     * 点是否落在条内部右侧三点区。
     *
     * @param x 相对条左缘的水平坐标
     * @param width 整条宽度，必须大于 0
     */
    fun isMoreHit(x: Float, width: Float): Boolean {
        require(width > 0f) { "width must be > 0" }
        return x >= width * (1f - MORE_HIT_FRACTION)
    }

    /**
     * 把水平偏移映射成 0..100 音量。左缘=0，右缘=100。
     *
     * @param x 相对条左缘的水平坐标
     * @param width 整条宽度，必须大于 0
     */
    fun volumeFromOffset(x: Float, width: Float): Int {
        require(width > 0f) { "width must be > 0" }
        val fraction = (x / width).coerceIn(0f, 1f)
        return (fraction * 100f).roundToInt().coerceIn(0, 100)
    }

    /**
     * 三点图标被白色填充盖住的比例。
     *
     * 填充还没到右侧三点区时为 0，完全盖住后为 1。用来在轨道白字和填充黑字之间过渡。
     *
     * @param fillFraction 当前填充 0..1
     * @param moreHitFraction 右侧三点区占整条宽度的比例
     */
    fun moreIconFillCoverage(
        fillFraction: Float,
        moreHitFraction: Float = MORE_HIT_FRACTION,
    ): Float {
        require(fillFraction in 0f..1f) { "fillFraction must be in 0..1" }
        require(moreHitFraction in 0f..1f) { "moreHitFraction must be in 0..1" }
        if (moreHitFraction == 0f) return if (fillFraction >= 1f) 1f else 0f
        val start = 1f - moreHitFraction
        return ((fillFraction - start) / moreHitFraction).coerceIn(0f, 1f)
    }

    /**
     * 按下后位移是否已经超过 slop，应该改成拖动而不是点更多。
     *
     * @param distance 相对按下点的位移
     * @param slop 系统 touch slop，必须大于 0
     */
    fun isDragPastSlop(distance: Float, slop: Float): Boolean {
        require(slop > 0f) { "slop must be > 0" }
        require(distance >= 0f) { "distance must not be negative" }
        return distance >= slop
    }
}

private val BarShape = RoundedCornerShape(22.dp)
private val FillShape = RoundedCornerShape(4.dp)
private val TrackColor = Color.White.copy(alpha = 0.18f)
private val FillColor = Color.White
private val InBarIconAlpha = 0.92f

/**
 * 系统音量栏同款大圆角方条，横放。
 *
 * 高度约 60dp，圆角 22dp，与设备选择行同一套圆角。
 * 应用图标在条内左侧，三点在条内右侧，都叠在填充上。
 */
@Composable
fun AppVolumeBar(
    volumePercent: Int,
    appIcon: Drawable,
    onVolumeChange: (Int) -> Unit,
    onVolumeChangeFinished: () -> Unit,
    onMoreClick: () -> Unit,
    moreContentDescription: String,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
) {
    require(volumePercent in 0..100) { "volumePercent must be in 0..100" }
    val currentOnVolumeChange by rememberUpdatedState(onVolumeChange)
    val currentOnFinished by rememberUpdatedState(onVolumeChangeFinished)
    val currentOnMore by rememberUpdatedState(onMoreClick)
    val iconBitmap = remember(appIcon) { appIcon.toBitmap(96, 96).asImageBitmap() }
    var dragging by remember { mutableStateOf(false) }
    val displayedFraction by animateFloatAsState(
        targetValue = volumePercent / 100f,
        animationSpec = if (dragging) {
            snap()
        } else {
            spring(dampingRatio = 0.86f, stiffness = 700f)
        },
        label = "volumeFill",
    )
    val pressScale by animateFloatAsState(
        targetValue = if (dragging) 0.985f else 1f,
        animationSpec = spring(dampingRatio = 0.9f, stiffness = 500f),
        label = "volumePress",
    )

    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(60.dp)
            .graphicsLayer {
                scaleX = pressScale
                scaleY = pressScale
            }
            .clip(BarShape)
            .background(TrackColor)
            .semantics {
                if (contentDescription != null) {
                    this.contentDescription = contentDescription
                }
                progressBarRangeInfo = ProgressBarRangeInfo(volumePercent / 100f, 0f..1f)
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val width = size.width.toFloat()
                    if (width <= 0f) return@awaitEachGesture
                    val slop = viewConfiguration.touchSlop
                    val startedOnMore = AppVolumeBarHit.isMoreHit(down.position.x, width)
                    var startX = down.position.x
                    if (startedOnMore) {
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
                            if (!change.pressed) {
                                currentOnMore()
                                return@awaitEachGesture
                            }
                            val distance = hypot(
                                change.position.x - down.position.x,
                                change.position.y - down.position.y,
                            )
                            if (AppVolumeBarHit.isDragPastSlop(distance, slop)) {
                                startX = change.position.x
                                change.consume()
                                break
                            }
                        }
                    } else {
                        down.consume()
                    }
                    dragging = true
                    currentOnVolumeChange(AppVolumeBarHit.volumeFromOffset(startX, width))
                    drag(down.id) { change ->
                        val dragWidth = size.width.toFloat()
                        if (dragWidth > 0f) {
                            currentOnVolumeChange(AppVolumeBarHit.volumeFromOffset(change.position.x, dragWidth))
                        }
                        change.consume()
                    }
                    dragging = false
                    currentOnFinished()
                }
            },
    ) {
        val fillWidth = maxWidth * displayedFraction
        if (fillWidth > 0.dp) {
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .fillMaxHeight()
                .width(fillWidth)
                .clip(FillShape)
                .background(FillColor),
            )
        }
        Image(
            bitmap = iconBitmap,
            contentDescription = null,
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = 14.dp)
                .size(36.dp)
                .clip(RoundedCornerShape(10.dp)),
            alpha = InBarIconAlpha,
        )
        val moreCoverage = AppVolumeBarHit.moreIconFillCoverage(displayedFraction)
        val moreTint = lerp(Color.White, Color.Black, moreCoverage).copy(alpha = InBarIconAlpha)
        Box(
            Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .fillMaxWidth(AppVolumeBarHit.MORE_HIT_FRACTION)
                .semantics {
                    this.contentDescription = moreContentDescription
                    onClick {
                        currentOnMore()
                        true
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = MoreHorizVector,
                contentDescription = null,
                modifier = Modifier.size(26.dp),
                tint = moreTint,
            )
        }
    }
}

/** 横三点，放在横条右侧。 */
private val MoreHorizVector: ImageVector by lazy {
    ImageVector.Builder(
        name = "HyperOsMoreHoriz",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(5.5f, 10.5f)
            arcToRelative(1.5f, 1.5f, 0f, true, true, 0f, 3f)
            arcToRelative(1.5f, 1.5f, 0f, true, true, 0f, -3f)
            close()
            moveTo(12f, 10.5f)
            arcToRelative(1.5f, 1.5f, 0f, true, true, 0f, 3f)
            arcToRelative(1.5f, 1.5f, 0f, true, true, 0f, -3f)
            close()
            moveTo(18.5f, 10.5f)
            arcToRelative(1.5f, 1.5f, 0f, true, true, 0f, 3f)
            arcToRelative(1.5f, 1.5f, 0f, true, true, 0f, -3f)
            close()
        }
    }.build()
}
