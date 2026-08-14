package hk.uwu.soundman.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

internal val OverlayGlassShape = RoundedCornerShape(36.dp)
internal val OverlayGlassFill = Color(0x663C3C3E)
internal val OverlayGlassBorder = Color.White.copy(alpha = 0.24f)
internal val HomeGlassShape = RoundedCornerShape(28.dp)
internal val HomeButtonShape = RoundedCornerShape(24.dp)

/**
 * 半透明玻璃底 + 细描边。主页卡片和悬浮面板共用同一套材质。
 */
@Composable
internal fun GlassSurface(
    modifier: Modifier = Modifier,
    shape: RoundedCornerShape = HomeGlassShape,
    fill: Color,
    border: Color = OverlayGlassBorder,
    onClick: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val clickable = if (onClick == null) {
        Modifier
    } else {
        Modifier.clickable(onClick = onClick)
    }
    Box(
        modifier
            .clip(shape)
            .background(fill)
            .border(1.dp, border, shape)
            .then(clickable),
        content = content,
    )
}
