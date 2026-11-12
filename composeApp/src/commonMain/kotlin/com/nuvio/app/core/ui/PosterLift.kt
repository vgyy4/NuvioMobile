package com.nuvio.app.core.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.RememberObserver
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.GraphicsContext
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalGraphicsContext
import androidx.compose.ui.unit.Dp

internal val LocalPosterClickAnchor = staticCompositionLocalOf<((PosterZoomAnchor) -> Unit)?> { null }

internal class PosterLiftSource(private val graphicsContext: GraphicsContext) : RememberObserver {
    val layer = graphicsContext.createGraphicsLayer()
    var bounds: Rect? = null
    var isLifted by mutableStateOf(false)
        private set
    private var rememberCount = 0

    val canLift: Boolean
        get() = rememberCount > 0 && !layer.isReleased && layer.size.width > 0 && layer.size.height > 0

    fun lift(): Boolean {
        if (!canLift) return false
        isLifted = true
        return true
    }

    fun land() {
        isLifted = false
        if (rememberCount == 0) release()
    }

    override fun onRemembered() {
        rememberCount++
    }

    override fun onForgotten() {
        rememberCount--
        if (rememberCount == 0 && !isLifted) release()
    }

    override fun onAbandoned() {
        if (rememberCount == 0 && !isLifted) release()
    }

    private fun release() {
        if (!layer.isReleased) graphicsContext.releaseGraphicsLayer(layer)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun Modifier.posterCardClickable(
    onClick: (() -> Unit)?,
    onLongClick: (() -> Unit)?,
    zoomImageUrl: String? = null,
    zoomCornerRadius: Dp = NuvioTokens.Radius.poster,
): Modifier {
    val graphicsContext = LocalGraphicsContext.current
    val source = remember(graphicsContext, zoomImageUrl) { PosterLiftSource(graphicsContext) }
    val onPosterClickAnchor = LocalPosterClickAnchor.current
    // The lift animation shows a copy of the poster. That copy used to be re-recorded on every
    // redraw of every poster on screen, a constant cost. It is only needed once the card is
    // touched, so it is recorded from the moment a finger lands on the card; the recording is
    // kept afterwards for as long as the animation needs it.
    val touched = remember { mutableStateOf(false) }
    val posterModifier = Modifier
        .pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                touched.value = true
                try {
                    waitForUpOrCancellation(pass = PointerEventPass.Initial)
                } finally {
                    touched.value = false
                }
            }
        }
        .drawWithContent {
            if (touched.value) {
                source.layer.record { this@drawWithContent.drawContent() }
                if (!source.isLifted) drawLayer(source.layer)
            } else if (!source.isLifted) {
                drawContent()
            }
        }
        .onGloballyPositioned { coordinates ->
            val position = coordinates.positionInRoot()
            source.bounds = Rect(
                left = position.x,
                top = position.y,
                right = position.x + coordinates.size.width,
                bottom = position.y + coordinates.size.height,
            )
        }
        .then(this)
    if (onClick == null && onLongClick == null) return posterModifier
    return posterModifier
        .combinedClickable(
            interactionSource = null,
            indication = if (onPosterClickAnchor == null) LocalIndication.current else null,
            onClick = {
                if (onClick != null) {
                    source.bounds?.let { bounds ->
                        onPosterClickAnchor?.invoke(
                            PosterZoomAnchor(bounds, zoomImageUrl, zoomCornerRadius).also { it.source = source },
                        )
                    }
                    onClick()
                }
            },
            onLongClick = onLongClick?.let { longClick ->
                {
                    source.bounds?.let { cardBounds ->
                        PosterZoomAnchorHolder.stash(
                            PosterZoomAnchor(cardBounds, zoomImageUrl, zoomCornerRadius).also {
                                it.source = source
                            },
                        )
                    }
                    longClick()
                }
            },
        )
}

internal fun DrawScope.drawLiftedPoster(source: PosterLiftSource) {
    val layer = source.layer
    if (layer.isReleased || layer.size.width <= 0 || layer.size.height <= 0) return
    scale(
        scaleX = size.width / layer.size.width,
        scaleY = size.height / layer.size.height,
        pivot = Offset.Zero,
    ) {
        drawLayer(layer)
    }
}
