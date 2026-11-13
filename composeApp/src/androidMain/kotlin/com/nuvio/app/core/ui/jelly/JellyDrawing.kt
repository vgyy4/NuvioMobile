package com.nuvio.app.core.ui.jelly

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.unit.dp
import kotlin.math.min

internal fun DrawScope.drawJellyGlow(frame: JellyFrame, color: Color) {
    if (frame.glowOpacity <= 0f || color.alpha <= 0f) return
    val alpha = 0.15f * frame.glowOpacity * color.alpha
    drawRect(
        brush = Brush.radialGradient(
            0f to color.copy(alpha = alpha),
            0.45f to color.copy(alpha = alpha * 0.43f),
            1f to color.copy(alpha = 0f),
            center = Offset(frame.originX.dp.toPx(), frame.glowY.dp.toPx()),
            radius = 300.dp.toPx(),
        ),
        topLeft = Offset(-48.dp.toPx(), -16.dp.toPx()),
        size = Size(size.width + 96.dp.toPx(), size.height + 32.dp.toPx()),
    )
}

internal fun DrawScope.jellyPillPath(frame: JellyFrame, count: Int): Path {
    val inset = 4.dp.toPx()
    val tabWidth = (size.width - 2 * inset) / count
    val itemHeight = size.height - 2 * inset
    val centerX = inset + (frame.position + 0.5f) * tabWidth
    // Equal outward growth (in px) on all four sides. A ratio-scaled wide pill grows far more at
    // its sides than at its top/bottom, and the velocity squash even shrank its height.
    val swell = tabWidth * frame.pillSwell / 2
    // Horizontal: the swell plus the velocity stretch (pillScaleX = shape spring / speed term).
    val halfWidth = tabWidth * frame.pillScaleX / 2
    // Vertical: resting height plus the same swell, never squashed. It may extend a few dp past
    // the bar; nothing clips the pill, and the fill/glow rects are taller than the largest swell.
    val halfHeight = itemHeight / 2 + swell
    val radius = min(halfWidth, halfHeight)
    return Path().apply {
        addRoundRect(
            RoundRect(
                left = centerX - halfWidth,
                top = size.height / 2 - halfHeight,
                right = centerX + halfWidth,
                bottom = size.height / 2 + halfHeight,
                cornerRadius = CornerRadius(radius, radius),
            ),
        )
    }
}

internal fun DrawScope.drawJellyPill(
    frame: JellyFrame,
    count: Int,
    surfaceColor: Color,
    glowColor: Color,
    content: () -> Unit,
) {
    clipPath(jellyPillPath(frame, count)) {
        drawRect(
            color = surfaceColor,
            topLeft = Offset(-48.dp.toPx(), -16.dp.toPx()),
            size = Size(size.width + 96.dp.toPx(), size.height + 32.dp.toPx()),
        )
        drawJellyGlow(frame, glowColor)
        content()
    }
}
