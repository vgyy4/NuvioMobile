package com.nuvio.app.core.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.RememberObserver
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.GraphicsContext
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalGraphicsContext
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

internal val LocalPosterClickAnchor = staticCompositionLocalOf<((PosterZoomAnchor) -> Unit)?> { null }

/**
 * Lets a card that is being held ask the host of the long-press overlay to prepare its blurred
 * backdrop while the finger is still down, so that work is not left for the frame in which the
 * menu opens. `true` while a long press is building up, `false` when it ends.
 */
internal val LocalPosterOverlayWarmUp = staticCompositionLocalOf<((Boolean) -> Unit)?> { null }

// How far a card shrinks, at most, while it is held (1f - this = 95 % of its size).
private const val PosterPressDepth = 0.05f

// A press shorter than this (a tap, or the first moments of a scroll) shows nothing yet.
private const val PosterPressFeedbackDelayMillis = 80

// Fraction of the long-press time after which the overlay backdrop is prepared.
private const val PosterPressWarmUpFraction = 0.5f

// How long a card that fired a long press waits for the overlay to lift it before it relaxes.
private const val PosterPressLiftGraceMillis = 1200L

private fun posterPressScale(charge: Float): Float {
    val progress = charge.coerceIn(0f, 1f)
    // Ease-out: most of the movement happens right after the touch, then it keeps creeping in
    // until the long press fires.
    val eased = 1f - (1f - progress) * (1f - progress)
    return 1f - PosterPressDepth * eased
}

/**
 * The "charging" press-in of a card that has a long-press action. [charge] runs from 0 to 1 over
 * the long-press time and is only ever read while drawing, so a press never recomposes a card.
 */
private class PosterPressState {
    val charge = Animatable(0f)

    /** The long press fired: the card stays pressed in until the overlay takes it over. */
    var held = false

    private var job: Job? = null

    fun scale(): Float = posterPressScale(charge.value)

    fun begin(scope: CoroutineScope, longPressTimeoutMillis: Long) {
        job?.cancel()
        held = false
        val chargeMillis = (longPressTimeoutMillis - PosterPressFeedbackDelayMillis).coerceAtLeast(1L).toInt()
        job = scope.launch {
            charge.animateTo(
                targetValue = 1f,
                animationSpec = tween(
                    durationMillis = chargeMillis,
                    delayMillis = PosterPressFeedbackDelayMillis,
                    easing = LinearEasing,
                ),
            )
        }
    }

    fun release(scope: CoroutineScope) {
        job?.cancel()
        job = scope.launch {
            charge.animateTo(0f, tween(durationMillis = 140, easing = FastOutSlowInEasing))
        }
    }

    /**
     * After a long press the card keeps its pressed-in size until the overlay lifts it (the overlay
     * starts from that size, so there is no jump). If nothing lifts it, it relaxes after a while.
     */
    fun settleAfterLongPress(scope: CoroutineScope, source: PosterLiftSource) {
        job?.cancel()
        job = scope.launch {
            val lifted = source.isLifted ||
                withTimeoutOrNull(PosterPressLiftGraceMillis) {
                    snapshotFlow { source.isLifted }.first { it }
                } != null
            if (lifted) {
                // The card is hidden while lifted; it is back at full size when it lands.
                charge.snapTo(0f)
            } else {
                charge.animateTo(0f, tween(durationMillis = 140, easing = FastOutSlowInEasing))
            }
        }
    }
}

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
    val overlayWarmUp by rememberUpdatedState(LocalPosterOverlayWarmUp.current)
    // The lift animation shows a copy of the poster. That copy used to be re-recorded on every
    // redraw of every poster on screen, a constant cost. It is only needed once the card is
    // touched, so it is recorded from the moment a finger lands on the card; the recording is
    // kept afterwards for as long as the animation needs it.
    val touched = remember { mutableStateOf(false) }
    // Cards with a long-press action show a press-in that charges up for the long-press time.
    val press = remember(onLongClick != null) { if (onLongClick != null) PosterPressState() else null }
    val posterModifier = Modifier
        .pointerInput(Unit) {
            coroutineScope {
                val gestureScope = this
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    touched.value = true
                    val touchSlop = viewConfiguration.touchSlop
                    val longPressTimeout = viewConfiguration.longPressTimeoutMillis
                    var warmedUp = false
                    var warmUpJob: Job? = null
                    if (press != null) {
                        press.begin(gestureScope, longPressTimeout)
                        warmUpJob = gestureScope.launch {
                            delay(
                                PosterPressFeedbackDelayMillis +
                                    ((longPressTimeout - PosterPressFeedbackDelayMillis) * PosterPressWarmUpFraction).toLong(),
                            )
                            warmedUp = true
                            overlayWarmUp?.invoke(true)
                        }
                    }
                    var moved = false
                    try {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            if (!moved && (change.position - down.position).getDistance() > touchSlop) {
                                // The finger moved far enough to be a scroll or a drag, which is
                                // also what cancels the long press itself.
                                moved = true
                                warmUpJob?.cancel()
                                if (press != null && !press.held) {
                                    press.release(gestureScope)
                                    if (warmedUp) {
                                        warmedUp = false
                                        overlayWarmUp?.invoke(false)
                                    }
                                }
                            }
                        }
                    } finally {
                        touched.value = false
                        warmUpJob?.cancel()
                        if (press != null) {
                            if (press.held) {
                                press.settleAfterLongPress(gestureScope, source)
                                if (warmedUp) {
                                    // The overlay is about to take over the backdrop; let it
                                    // keep it instead of tearing it down and building it again.
                                    gestureScope.launch {
                                        delay(PosterPressLiftGraceMillis)
                                        overlayWarmUp?.invoke(false)
                                    }
                                }
                            } else {
                                if (!moved) press.release(gestureScope)
                                if (warmedUp) overlayWarmUp?.invoke(false)
                            }
                        }
                    }
                }
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
        .then(
            if (press != null) {
                // Scales the whole card in the draw phase only. It sits outside everything the
                // caller adds, and the bounds above are measured outside it, so the lift still
                // starts from the card's real position.
                Modifier.graphicsLayer {
                    val scale = if (source.isLifted) 1f else press.scale()
                    scaleX = scale
                    scaleY = scale
                }
            } else {
                Modifier
            },
        )
        .drawWithContent {
            if (touched.value) {
                source.layer.record { this@drawWithContent.drawContent() }
                if (!source.isLifted) drawLayer(source.layer)
            } else if (!source.isLifted) {
                drawContent()
            }
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
                    press?.held = true
                    source.bounds?.let { cardBounds ->
                        PosterZoomAnchorHolder.stash(
                            PosterZoomAnchor(cardBounds, zoomImageUrl, zoomCornerRadius).also {
                                it.source = source
                                it.pressScale = press?.scale() ?: 1f
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
