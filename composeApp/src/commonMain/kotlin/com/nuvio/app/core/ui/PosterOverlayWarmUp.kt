package com.nuvio.app.core.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalGraphicsContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import dev.chrisbanes.haze.HazeState

/** Remembers, for the life of the process, that the overlay pipelines have been warmed up. */
internal object PosterOverlayWarmUpGate {
    var completed = false
}

/** Shows the warm-up while [running] says so; reading it here keeps the caller from recomposing. */
@Composable
internal fun PosterOverlayWarmUpHost(
    running: () -> Boolean,
    hazeState: HazeState,
    onFinished: () -> Unit,
) {
    if (running()) {
        PosterOverlayWarmUp(hazeState = hazeState, onFinished = onFinished)
    }
}

private const val WarmUpPosterWidthPx = 120
private const val WarmUpPosterHeightPx = 180

/**
 * Plays the long-press overlay once (open, hold, close) behind the app's opaque content, so the
 * first long press of a session does not pay for the one-time work: compiling the GPU pipelines for
 * the blur, scrim, shadow, clipped/alpha layers and the lifted-poster draw, sizing the blur's
 * render targets, loading the menu's text and icons, and running the composition code for the first
 * time.
 *
 * It is the real [NuvioPosterZoomActionOverlay] in warm-up mode (same composition, same
 * animations, same blur source), with a recorded poster layer of the same kind a card records.
 * It must be placed below the content that carries the blur source: that content is opaque, so
 * nothing of this is ever visible, but the renderer still draws every layer of it.
 */
@Composable
private fun PosterOverlayWarmUp(
    hazeState: HazeState,
    onFinished: () -> Unit,
) {
    val graphicsContext = LocalGraphicsContext.current
    val density = LocalDensity.current
    val source = remember(graphicsContext) { PosterLiftSource(graphicsContext) }
    val anchor = remember(source) {
        recordWarmUpPoster(source, density)
        PosterZoomAnchor(
            boundsInRoot = Rect(
                left = 48f,
                top = 96f,
                right = 48f + WarmUpPosterWidthPx,
                bottom = 96f + WarmUpPosterHeightPx,
            ),
            imageUrl = null,
            cornerRadius = NuvioTokens.Radius.poster,
        ).also { it.source = source }
    }
    val actions = remember {
        listOf(
            PosterZoomOverlayAction(icon = Icons.Default.Add, label = "Add to library", onSelected = {}),
            PosterZoomOverlayAction(icon = Icons.Default.CheckCircle, label = "Mark as watched", onSelected = {}),
            PosterZoomOverlayAction(
                icon = Icons.Default.DeleteOutline,
                label = "Remove",
                isDestructive = true,
                onSelected = {},
            ),
        )
    }
    Box(modifier = Modifier.fillMaxSize().clearAndSetSemantics {}) {
        NuvioPosterZoomActionOverlay(
            imageUrl = null,
            title = "Warm up",
            subtitle = "Movie",
            anchor = anchor,
            actions = actions,
            hazeState = hazeState,
            onDismissed = onFinished,
            warmUp = true,
        )
    }
}

/** Records the kinds of draw calls a poster card has: rounded clip, bitmap, gradients, edge stroke. */
private fun recordWarmUpPoster(source: PosterLiftSource, density: Density) {
    val bitmap = ImageBitmap(16, 16)
    val width = WarmUpPosterWidthPx.toFloat()
    val height = WarmUpPosterHeightPx.toFloat()
    val radius = CornerRadius(18f, 18f)
    source.layer.record(density, LayoutDirection.Ltr, IntSize(WarmUpPosterWidthPx, WarmUpPosterHeightPx)) {
        val outline = Path().apply { addRoundRect(RoundRect(0f, 0f, width, height, radius)) }
        clipPath(outline) {
            drawImage(image = bitmap, dstSize = IntSize(WarmUpPosterWidthPx, WarmUpPosterHeightPx))
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(Color.White.copy(alpha = 0.2f), Color.Transparent),
                    startY = 0f,
                    endY = height * 0.22f,
                ),
            )
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.6f)),
                ),
            )
        }
        drawRoundRect(
            brush = Brush.verticalGradient(
                colors = listOf(Color.White.copy(alpha = 0.3f), Color.White.copy(alpha = 0.05f)),
            ),
            cornerRadius = radius,
            style = Stroke(width = 2f),
        )
    }
}
