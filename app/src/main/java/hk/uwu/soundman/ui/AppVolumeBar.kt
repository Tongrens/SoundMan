package hk.uwu.soundman.ui

import android.graphics.drawable.Drawable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
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
import top.yukonga.miuix.kmp.basic.Icon
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * 横向厚胶囊音量栏的命中与取值。
 *
 * 动机：形状跟系统音量栏同一套胶囊，只是横放。三点在右侧：点按打开更多，滑动仍调音量。
 */
object AppVolumeBarHit {
    const val MORE_HIT_FRACTION = 0.16f
    const val VOLUME_MIN = 0f
    const val VOLUME_MAX = 100f
    const val RUBBER_BAND_COEFFICIENT = 0.55f
    const val EDGE_PULL_GAIN = 0.4f
    const val EDGE_PULL_MAX = 0.06f

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
     * 相对滑动：按下时音量不变，左右移动才加减进度。
     *
     * 范围内线性跟手；越出 0/100 后橡胶阻尼，位移越大越沉。
     * 点按不会跳到手指所在位置。
     *
     * @param startVolume 按下时的音量 0..100
     * @param deltaX 相对按下点的水平位移，右为正
     * @param width 整条宽度，必须大于 0
     */
    fun volumeFromRelativeDrag(startVolume: Int, deltaX: Float, width: Float): Float {
        require(startVolume in 0..100) { "startVolume must be in 0..100" }
        require(width > 0f) { "width must be > 0" }
        require(deltaX.isFinite()) { "deltaX must be finite" }
        val linear = startVolume + (deltaX / width) * VOLUME_MAX
        return rubberBand(linear)
    }

    /**
     * 越出 [min, max] 的位移按橡胶带衰减，范围内原样返回。
     *
     * @param value 线性音量，可以越界
     * @param min 下界
     * @param max 上界，必须大于 [min]
     * @param coefficient 阻尼系数，必须大于 0
     */
    fun rubberBand(
        value: Float,
        min: Float = VOLUME_MIN,
        max: Float = VOLUME_MAX,
        coefficient: Float = RUBBER_BAND_COEFFICIENT,
    ): Float {
        require(value.isFinite()) { "value must be finite" }
        require(max > min) { "max must be > min" }
        require(coefficient > 0f) { "coefficient must be > 0" }
        if (value in min..max) return value
        val dimension = max - min
        return if (value > max) {
            max + rubberBandOverflow(value - max, dimension, coefficient)
        } else {
            min - rubberBandOverflow(min - value, dimension, coefficient)
        }
    }

    /**
     * 单向越界位移的橡胶衰减。
     *
     * `overflow=0` 仍为 0；越大越接近 [dimension]，永远到不了线性越界那么远。
     */
    fun rubberBandOverflow(overflow: Float, dimension: Float, coefficient: Float): Float {
        require(overflow >= 0f) { "overflow must not be negative" }
        require(overflow.isFinite()) { "overflow must be finite" }
        require(dimension > 0f) { "dimension must be > 0" }
        require(coefficient > 0f) { "coefficient must be > 0" }
        return (1f - 1f / (overflow * coefficient / dimension + 1f)) * dimension
    }

    /**
     * 越出 0/100 时条的拉伸量，范围内为 0。
     */
    fun edgePull(volume: Float): Float {
        require(volume.isFinite()) { "volume must be finite" }
        val overflow = when {
            volume > VOLUME_MAX -> volume - VOLUME_MAX
            volume < VOLUME_MIN -> VOLUME_MIN - volume
            else -> 0f
        }
        return (overflow / VOLUME_MAX * EDGE_PULL_GAIN).coerceIn(0f, EDGE_PULL_MAX)
    }

    /**
     * 拖动填充比例，越界时夹在 0..1。
     */
    fun fillFraction(volume: Float): Float {
        require(volume.isFinite()) { "volume must be finite" }
        return (volume / VOLUME_MAX).coerceIn(0f, 1f)
    }

    /**
     * 松手写入规则的整数音量。
     */
    fun committedPercent(volume: Float): Int {
        require(volume.isFinite()) { "volume must be finite" }
        return volume.roundToInt().coerceIn(0, 100)
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
private val TrackColor = Color.White.copy(alpha = 0.18f)
private val FillColor = Color.White
private val InBarIconAlpha = 0.92f
private const val DRAG_DAMPING = 0.84f
private const val DRAG_STIFFNESS = 1600f
private const val REST_DAMPING = 0.86f
private const val REST_STIFFNESS = 700f

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
    val currentVolume by rememberUpdatedState(volumePercent)
    val iconBitmap = remember(appIcon) { appIcon.toBitmap(96, 96).asImageBitmap() }
    var dragging by remember { mutableStateOf(false) }
    var dragVolume by remember { mutableFloatStateOf(0f) }
    val displayedFraction by animateFloatAsState(
        targetValue = if (dragging) {
            AppVolumeBarHit.fillFraction(dragVolume)
        } else {
            volumePercent / 100f
        },
        animationSpec = spring(
            dampingRatio = if (dragging) DRAG_DAMPING else REST_DAMPING,
            stiffness = if (dragging) DRAG_STIFFNESS else REST_STIFFNESS,
        ),
        label = "volumeFill",
    )
    val edgePull by animateFloatAsState(
        targetValue = if (dragging) AppVolumeBarHit.edgePull(dragVolume) else 0f,
        animationSpec = spring(dampingRatio = DRAG_DAMPING, stiffness = DRAG_STIFFNESS),
        label = "edgePull",
    )
    val pressScale by animateFloatAsState(
        targetValue = if (dragging) 0.985f else 1f,
        animationSpec = spring(dampingRatio = 0.9f, stiffness = 500f),
        label = "volumePress",
    )

    Box(
        modifier
            .fillMaxWidth()
            .height(60.dp)
            .graphicsLayer {
                scaleX = pressScale * (1f + edgePull)
                scaleY = pressScale * (1f - edgePull * 0.35f)
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
                    val startVolume = currentVolume
                    val startedOnMore = AppVolumeBarHit.isMoreHit(down.position.x, width)
                    var releasedWithoutDrag = false
                    var slopX = down.position.x
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id }
                            ?: return@awaitEachGesture
                        if (!change.pressed) {
                            releasedWithoutDrag = true
                            break
                        }
                        val distance = hypot(
                            change.position.x - down.position.x,
                            change.position.y - down.position.y,
                        )
                        if (AppVolumeBarHit.isDragPastSlop(distance, slop)) {
                            slopX = change.position.x
                            change.consume()
                            break
                        }
                    }
                    if (releasedWithoutDrag) {
                        if (startedOnMore) currentOnMore()
                        return@awaitEachGesture
                    }
                    var lastPercent = startVolume
                    fun applyDrag(deltaX: Float, barWidth: Float) {
                        val volume =
                            AppVolumeBarHit.volumeFromRelativeDrag(startVolume, deltaX, barWidth)
                        dragVolume = volume
                        val percent = AppVolumeBarHit.committedPercent(volume)
                        if (percent != lastPercent) {
                            lastPercent = percent
                            currentOnVolumeChange(percent)
                        }
                    }
                    dragging = true
                    applyDrag(slopX - down.position.x, width)
                    drag(down.id) { change ->
                        val dragWidth = size.width.toFloat()
                        if (dragWidth > 0f) {
                            applyDrag(change.position.x - down.position.x, dragWidth)
                        }
                        change.consume()
                    }
                    dragging = false
                    val finished = AppVolumeBarHit.committedPercent(dragVolume)
                    if (finished != lastPercent) currentOnVolumeChange(finished)
                    currentOnFinished()
                }
            },
    ) {
        Box(
            Modifier
                .matchParentSize()
                .drawBehind {
                    val fillWidth = size.width * displayedFraction.coerceIn(0f, 1f)
                    if (fillWidth <= 0f) return@drawBehind
                    drawRoundRect(
                        color = FillColor,
                        size = Size(fillWidth, size.height),
                        cornerRadius = CornerRadius(4.dp.toPx()),
                    )
                },
        )
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
        val moreCoverage = AppVolumeBarHit.moreIconFillCoverage(displayedFraction.coerceIn(0f, 1f))
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
