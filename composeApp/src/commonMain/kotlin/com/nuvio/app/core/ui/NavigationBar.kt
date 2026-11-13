package com.nuvio.app.core.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.offset
import dev.chrisbanes.haze.HazeInputScale
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Scroll-aware state for the floating navigation bar.
 * Tracks scroll direction and exposes a label visibility fraction (1 = fully visible, 0 = hidden).
 */
@Stable
class NuvioNavBarScrollState {
    /** 1f = labels fully visible (expanded), 0f = labels hidden (collapsed, icons only) */
    var labelVisibility by mutableFloatStateOf(1f)
        private set

    private var accumulatedDelta = 0f

    /**
     * When true the bar also expands as soon as a downward drag reaches the start of the content
     * (nothing left to scroll up), so scrolling back to the top always leaves the compact state.
     */
    var expandsAtTop = false

    /** Call to expand (show labels) – e.g. when user scrolls back to top */
    fun expand() {
        labelVisibility = 1f
        accumulatedDelta = 0f
    }

    /** Call to collapse (hide labels) */
    fun collapse() {
        labelVisibility = 0f
        accumulatedDelta = 0f
    }

    val nestedScrollConnection: NestedScrollConnection = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            val deltaY = available.y
            if (deltaY == 0f) return Offset.Zero

            accumulatedDelta += deltaY

            if (accumulatedDelta < -SCROLL_THRESHOLD && labelVisibility != 0f) {
                // Scrolling down past threshold → snap collapse
                labelVisibility = 0f
                accumulatedDelta = 0f
            } else if (accumulatedDelta > SCROLL_THRESHOLD && labelVisibility != 1f) {
                // Scrolling up past threshold → snap expand
                labelVisibility = 1f
                accumulatedDelta = 0f
            }

            // Reset accumulator if direction changed
            if (deltaY < 0f && accumulatedDelta > 0f) accumulatedDelta = deltaY
            if (deltaY > 0f && accumulatedDelta < 0f) accumulatedDelta = deltaY

            return Offset.Zero // Don't consume any scroll
        }

        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
            // Pulling down with nothing left to scroll: the content is at its start.
            if (expandsAtTop && available.y > 0f && labelVisibility != 1f) {
                labelVisibility = 1f
                accumulatedDelta = 0f
            }
            return Offset.Zero
        }
    }

    companion object {
        private const val SCROLL_THRESHOLD = 60f
    }
}

@Composable
fun rememberNuvioNavBarScrollState(): NuvioNavBarScrollState {
    return androidx.compose.runtime.remember { NuvioNavBarScrollState() }
}

/**
 * Touch handling of the glass bar, a port of the Android jelly bar's `detectJellyTabGestures`.
 * Touch-down only observes (the tab under the finger starts the capsule's swell; a plain tap still
 * reaches the item's own click). Once the finger has moved past the touch slop the gesture is
 * claimed: the capsule follows the finger across the tabs and the nearest tab is selected on
 * release. A press on the profile tab that has been held long enough to open its popup is left to
 * that popup.
 */
private fun Modifier.glassPressFeedback(pillState: NavPillState): Modifier = pointerInput(pillState) {
    val slop = viewConfiguration.touchSlop
    val longPressMillis = viewConfiguration.longPressTimeoutMillis
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        pillState.onPress(down.position.x, down.position.y)
        val holdsPopup = pillState.holdsPopupAt(down.position.x)
        var claimed = false
        var handled = false
        try {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (!claimed) {
                    val delta = change.position - down.position
                    if (max(abs(delta.x), abs(delta.y)) > slop) {
                        val held = change.uptimeMillis - down.uptimeMillis >= longPressMillis
                        if (holdsPopup && held) break
                        claimed = pillState.beginDrag()
                    }
                }
                if (claimed) {
                    change.consume()
                    pillState.onDrag(change.position.x - down.position.x)
                    if (!change.pressed) {
                        val chosen = pillState.endDrag()
                        handled = true
                        chosen?.onClick?.invoke()
                        break
                    }
                } else if (!change.pressed) {
                    break
                }
            }
        } finally {
            if (claimed && !handled) pillState.abortDrag()
            pillState.onRelease()
        }
    }
}

/**
 * True when the bar should imitate the iOS 26 Liquid Glass tab bar (Compose shell on iOS, where the
 * system bar is not available). Provided by the main tabs destination.
 */
internal val LocalNuvioGlassNavigationBar = staticCompositionLocalOf { false }

private val GlassBaseColor = Color(0xFF1C1C1E)

/** Horizontal inset of the expanded pill; the glass layer always has the expanded width. */
private val GlassExpandedInset = 28.dp

/** Constant height of the glass layer: at least the expanded pill (65 dp), clipped to the real size. */
private val GlassLayerHeight = 68.dp

/**
 * Visible rect (size) of the pill as laid out right now. Written by the pill's layout, read only in
 * the draw/layer phases, so the compact <-> expanded animation never recomposes or re-sizes the
 * blur: the glass layer keeps a constant size and only a clip window and the edge drawings follow.
 */
@Stable
private class GlassWindow {
    var width by mutableFloatStateOf(0f)
    var height by mutableFloatStateOf(0f)
}

/** Fully rounded window, bottom-aligned and centred in the (larger, constant-size) glass layer. */
private data class GlassWindowShape(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
        Outline.Rounded(RoundRect(left, top, right, bottom, CornerRadius((bottom - top) / 2f)))
}

/**
 * Soft drop shadow of the system bar: stacked translucent rounded rects drawn only outside the
 * pill outline (so the translucent glass is not darkened from behind). Draw phase only; the outline
 * is the live [window] rect.
 */
private fun Modifier.glassBarShadow(window: GlassWindow): Modifier = drawBehind {
    val windowWidth = window.width
    val windowHeight = window.height
    if (windowWidth <= 0f || windowHeight <= 0f) return@drawBehind
    val left = (size.width - windowWidth) / 2f
    val top = size.height - windowHeight
    val outline = Path().apply {
        addRoundRect(RoundRect(left, top, left + windowWidth, top + windowHeight, CornerRadius(windowHeight / 2f)))
    }
    val offsetY = 6.dp.toPx()
    clipPath(outline, ClipOp.Difference) {
        for (layer in 1..8) {
            val spread = layer * 2.dp.toPx()
            drawRoundRect(
                color = Color.Black.copy(alpha = 0.03f),
                topLeft = Offset(left - spread, top - spread + offsetY),
                size = Size(windowWidth + 2f * spread, windowHeight + 2f * spread),
                cornerRadius = CornerRadius(windowHeight / 2f + spread),
            )
        }
    }
}

/**
 * Specular edge of the glass: a lit rim (bright at the top-left and, fainter, at the bottom-right),
 * a soft top gloss and a small highlight near the top-left corner, drawn on the live [window] rect
 * (draw phase only; plain gradients, no blur).
 */
private fun Modifier.glassBarHighlights(window: GlassWindow): Modifier = drawBehind {
    val windowWidth = window.width
    val windowHeight = window.height
    if (windowWidth <= 0f || windowHeight <= 0f) return@drawBehind
    val left = (size.width - windowWidth) / 2f
    val top = size.height - windowHeight
    val rimWidth = 1.dp.toPx()
    drawRect(
        brush = Brush.verticalGradient(
            colors = listOf(Color.White.copy(alpha = 0.10f), Color.White.copy(alpha = 0.02f), Color.Transparent),
            startY = top,
            endY = top + windowHeight,
        ),
        topLeft = Offset(left, top),
        size = Size(windowWidth, windowHeight),
    )
    drawRect(
        brush = Brush.radialGradient(
            colors = listOf(Color.White.copy(alpha = 0.14f), Color.Transparent),
            center = Offset(left + windowHeight * 0.7f, top),
            radius = windowHeight * 1.5f,
        ),
        topLeft = Offset(left, top),
        size = Size(windowWidth, windowHeight),
    )
    drawRoundRect(
        brush = Brush.linearGradient(
            colors = listOf(
                Color.White.copy(alpha = 0.55f),
                Color.White.copy(alpha = 0.07f),
                Color.White.copy(alpha = 0.05f),
                Color.White.copy(alpha = 0.28f),
            ),
            start = Offset(left, top),
            end = Offset(left + windowWidth, top + windowHeight),
        ),
        topLeft = Offset(left + rimWidth / 2f, top + rimWidth / 2f),
        size = Size(windowWidth - rimWidth, windowHeight - rimWidth),
        cornerRadius = CornerRadius((windowHeight - rimWidth) / 2f),
        style = Stroke(rimWidth),
    )
}

/**
 * The glass itself: haze blur + tint, lit rim/gloss and shadow. Its size is CONSTANT (expanded
 * width x [GlassLayerHeight]); the visible pill is a rounded clip window (draw-phase layer
 * properties) over it. Resizing the blurred node every frame of the compact <-> expanded animation
 * would re-allocate/re-render the blur's render target each frame; with a constant node only a
 * clip outline changes, which does not touch the blur at all.
 */
@Composable
private fun GlassBarLayer(
    window: GlassWindow,
    pillState: NavPillState,
    hazeState: HazeState?,
    blurInputScale: HazeInputScale,
) {
    Box(
        modifier = Modifier
            .layout { measurable, constraints ->
                val width = (constraints.maxWidth - 2 * GlassExpandedInset.roundToPx()).coerceAtLeast(0)
                val height = GlassLayerHeight.roundToPx()
                val placeable = measurable.measure(Constraints.fixed(width, height))
                layout(width, height) { placeable.place(0, 0) }
            }
            .graphicsLayer {
                // Touch-down scale about the centre of the visible pill (same point as the items).
                val scale = pillState.trackScale
                scaleX = scale
                scaleY = scale
                val originY = if (size.height > 0f) (size.height - window.height / 2f) / size.height else 0.5f
                transformOrigin = TransformOrigin(0.5f, originY)
            }
            .glassBarShadow(window),
    ) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .graphicsLayer {
                    val windowWidth = window.width
                    val left = (size.width - windowWidth) / 2f
                    shape = GlassWindowShape(left, size.height - window.height, left + windowWidth, size.height)
                    clip = true
                }
                .then(
                    if (hazeState != null) {
                        Modifier.hazeEffect(state = hazeState) {
                            blurRadius = 30.dp
                            inputScale = blurInputScale
                            noiseFactor = 0f
                        }
                    } else {
                        Modifier
                    },
                )
                .background(GlassBaseColor.copy(alpha = if (hazeState == null) 0.82f else 0.42f))
                .glassBarHighlights(window),
        )
    }
}

/**
 * Floating pill-shaped navigation bar with scroll-responsive labels.
 *
 * @param hazeState Optional [HazeState] whose source is placed on the content behind this bar.
 *                  When provided, the pill gets a blur-through effect.
 */
@Composable
fun NuvioNavigationBar(
    modifier: Modifier = Modifier,
    scrollState: NuvioNavBarScrollState? = null,
    hazeState: HazeState? = null,
    contentPadding: PaddingValues = floatingNavigationBarPadding(),
    compactSize: Boolean = false,
    content: @Composable NuvioNavigationBarScope.() -> Unit,
) {
    val glass = LocalNuvioGlassNavigationBar.current
    // Compact <-> expanded (pill width, label height and fade). The glass bar uses a spring like the
    // system bar's minimize animation; the value is clamped because a spring may overshoot and the
    // label layout needs a non-negative fraction.
    val labelSpec: AnimationSpec<Float> = if (glass) {
        spring(dampingRatio = 0.9f, stiffness = 380f)
    } else {
        tween(
            durationMillis = NuvioTokens.Motion.sheetEnterMillis,
            easing = NuvioTokens.Motion.standard,
        )
    }
    val animatedLabelFraction = animateFloatAsState(
        targetValue = scrollState?.labelVisibility ?: 1f,
        animationSpec = labelSpec,
        label = "nav_label_alpha",
    )
    val labelFraction = remember(animatedLabelFraction) {
        derivedStateOf { animatedLabelFraction.value.coerceIn(0f, 1f) }
    }
    // The blur input is shrunk before blurring; a quarter-resolution input is indistinguishable
    // behind a ~30 dp blur and much cheaper on old GPUs (iPad Air 2).
    val blurInputScale = if (DevicePerformance.isLowEnd) HazeInputScale.Fixed(0.25f) else HazeInputScale.Auto

    val scope = rememberCoroutineScope()
    val pillState = remember { NavPillState(scope) }
    val glassWindow = remember { GlassWindow() }
    pillState.glassMode = glass
    val selectedPillColor = MaterialTheme.nuvio.colors.accent.copy(alpha = NuvioTokens.Opacity.selected)

    // Dynamic horizontal padding: pill shrinks when labels are hidden — driven by same labelFraction.
    // Applied while measuring, so the animation does not recompose the bar and its items.
    val expandedHorizontalPadding = GlassExpandedInset
    val collapsedHorizontalPadding = 58.dp

    // Outer container — no background, just safe padding
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(contentPadding),
        contentAlignment = Alignment.BottomCenter,
    ) {
        if (glass) {
            GlassBarLayer(glassWindow, pillState, hazeState, blurInputScale)
        }
        // The floating pill
        val pillModifier = Modifier
            .layout { measurable, constraints ->
                val horizontalPadding = expandedHorizontalPadding +
                    (collapsedHorizontalPadding - expandedHorizontalPadding) * (1f - labelFraction.value)
                val padding = horizontalPadding.roundToPx()
                val placeable = measurable.measure(constraints.offset(horizontal = -2 * padding))
                layout(
                    (placeable.width + 2 * padding).coerceIn(constraints.minWidth, constraints.maxWidth),
                    placeable.height,
                ) {
                    if (glass) {
                        glassWindow.width = placeable.width.toFloat()
                        glassWindow.height = placeable.height.toFloat()
                    }
                    placeable.placeRelative(padding, 0)
                }
            }
            .fillMaxWidth()
            .then(
                if (glass) {
                    // The glass (blur, tint, rim, shadow) is the separate constant-size layer above;
                    // this only scales the content on touch-down (draw phase). It is not clipped: like Android
                    // the capsule may swell a little past the glass.
                    Modifier.graphicsLayer {
                        val scale = pillState.trackScale
                        scaleX = scale
                        scaleY = scale
                    }
                } else {
                    Modifier
                        .clip(RoundedCornerShape(NuvioTokens.Radius.full))
                        .then(
                            if (hazeState != null) {
                                Modifier.hazeEffect(state = hazeState) {
                                    blurRadius = 24.dp
                                }
                            } else {
                                Modifier
                            },
                        )
                        .background(GlassBaseColor.copy(alpha = if (hazeState != null) 0.55f else 0.82f))
                },
            )

        Box(modifier = pillModifier) {
            // Padded content box shared by the sliding selection pill and the item row, so both use
            // the same coordinate origin (item bounds are measured relative to the row).
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        // Glass: the selected capsule (= item bounds) sits exactly 4 dp inside the
                        // pill on all four sides (the items have no horizontal padding of their own
                        // and the row fills this box), in the expanded and the compact state.
                        horizontal = if (glass) NuvioTokens.Space.s4 else NuvioTokens.Space.s6,
                        vertical = NuvioTokens.Space.s4,
                    )
                    .then(if (glass) Modifier.glassPressFeedback(pillState) else Modifier),
            ) {
                // Own layer + draw-phase reads only: the slide invalidates this layer, never the
                // blurred pill background or the items, and never recomposes anything.
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        // Glass: no own layer, so the swollen capsule is never culled at the layer
                        // bounds; it may draw a few dp past the bar on every side (like Android).
                        .then(if (glass) Modifier else Modifier.graphicsLayer())
                        .drawBehind { with(pillState) { drawSelectionPill(selectedPillColor, glass) } },
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    NuvioNavigationBarScopeImpl(
                        rowScope = this,
                        labelFraction = labelFraction,
                        compactSize = compactSize,
                        pillState = pillState,
                        glass = glass,
                    ).content()
                }
            }
        }
    }
}

/**
 * Last measured bounds of one nav item, relative to the item row. Plain fields (not snapshot
 * state): they are written from the layout phase and only read from the draw phase.
 */
private class NavItemBounds {
    var selected = false
    var placed = false
    var left = 0f
    var top = 0f
    var width = 0f
    var height = 0f

    /** Click of the item, so a drag that ends on it can select it. */
    var onClick: (() -> Unit)? = null

    /** True for the profile tab, whose own long-press popup takes over a held press. */
    var holdsPopup = false
}

/**
 * Spring used by the glass capsule: the same closed-form integrator as the Android jelly bar's
 * `JellySpring` (stiffness/damping ratio, critically damped when the ratio is exactly 1).
 */
private class GlassSpring(
    var value: Double,
    private val stiffness: Double,
    private val dampingRatio: Double,
) {
    var velocity = 0.0

    fun isAtRest(target: Double): Boolean = abs(value - target) < 0.0001 && abs(velocity) < 0.0001

    fun advance(target: Double, seconds: Double) {
        if (isAtRest(target)) {
            value = target
            velocity = 0.0
            return
        }
        val displacement = value - target
        val frequency = sqrt(stiffness)
        if (dampingRatio == 1.0) {
            val decay = exp(-frequency * seconds)
            val coefficient = velocity + frequency * displacement
            value = target + (displacement + coefficient * seconds) * decay
            velocity = (velocity - frequency * coefficient * seconds) * decay
        } else {
            val damping = dampingRatio * frequency
            val damped = frequency * sqrt(1 - dampingRatio * dampingRatio)
            val decay = exp(-damping * seconds)
            val cosine = cos(damped * seconds)
            val sine = sin(damped * seconds)
            val positionCoefficient = (velocity + damping * displacement) / damped
            val velocityCoefficient = (damping * velocity + stiffness * displacement) / damped
            value = target + decay * (displacement * cosine + positionCoefficient * sine)
            velocity = decay * (velocity * cosine - velocityCoefficient * sine)
        }
    }
}

/**
 * Drives the selection pill that slides from the previously selected item to the new one.
 * Same spring as the Android floating bar's position spring (critically damped, stiffness 1000).
 *
 * The slide is a single 0..1 [Animatable] interpolating from the rect the pill was drawn at when
 * the selection changed to the live bounds of the selected item. [progress] and [layoutTick] are
 * only read in the draw phase, so nothing recomposes per frame.
 *
 * The glass bar additionally reproduces the Android jelly bar's TAP behaviour (JellyMotion.begin /
 * finish / advance / publish): on touch-down the capsule starts moving to the tab under the finger
 * and swells (shape springs towards 1.3, press spring towards 1), the bar scales to 1.025, and a
 * velocity spring (fed with the slide velocity while the finger is down) stretches the capsule
 * along the slide. On release the shape returns to 1 once the capsule has arrived. All values are
 * published as snapshot floats that are read only in the draw/layer phases.
 */
private class NavPillState(private val scope: CoroutineScope) {
    private val progress = Animatable(1f)
    private var target: NavItemBounds? = null
    private var committed: NavItemBounds? = null
    private var hasFrom = false
    private var fromLeft = 0f
    private var fromTop = 0f
    private var fromWidth = 0f
    private var fromHeight = 0f
    private var travelTabs = 0f

    private val items = mutableListOf<NavItemBounds>()

    // Android JellyMotion springs (stiffness, damping ratio) for the tap feel.
    private val velocity = GlassSpring(0.0, 300.0, 0.5)
    private val press = GlassSpring(0.0, 1000.0, 1.0)
    private val shapeX = GlassSpring(1.0, 250.0, 0.6)
    private val distortionDamping = 18.0 / (2 * sqrt(240.0 * 0.9))
    private val trackPress = GlassSpring(1.0, 240.0 / 0.9, distortionDamping)
    private var pressed = false
    private var releasePending = false
    private var pressTarget = 0.0
    private var shapeTarget = 1.0
    private var loop: Job? = null

    // Hold-and-drag: the capsule follows the finger (Android `JellyMotion.drag`), the nearest tab wins.
    private var ordered: List<NavItemBounds> = emptyList()
    private val dragSpring = GlassSpring(0.0, 1000.0, 1.0)
    private var dragTarget = 0.0
    private var dragStartIndex = 0.0
    private var dragging = false
    private var dragLeft = 0f
    private var dragTop = 0f
    private var dragWidth = 0f
    private var dragHeight = 0f

    /** True while the capsule is being dragged; read in the draw/layer phases only. */
    var dragActive by mutableStateOf(false)
        private set

    /** Fractional tab index of the dragged capsule (0 = first tab on the left). */
    var dragIndex by mutableFloatStateOf(0f)
        private set

    /** Set by the bar: the tap feel below is only for the glass bar; the classic bar is unchanged. */
    var glassMode = false

    /** Capsule width scale (Android `pillScaleX`): shape spring divided by the speed (stretch) term. */
    var pillScaleX by mutableFloatStateOf(1f)
        private set

    /**
     * Pure swell (shape spring - 1, no velocity term). The capsule grows by `width * pillSwell / 2`
     * outward on every side, left/right and top/bottom alike.
     */
    var pillSwell by mutableFloatStateOf(0f)
        private set

    /** Scale of the content under the capsule (Android `contentScale`, 1 + 0.2 * press). */
    var contentScale by mutableFloatStateOf(1f)
        private set

    /** Whole-bar scale while the finger is down (Android `trackScale`, 1.025 at most). */
    var trackScale by mutableFloatStateOf(1f)
        private set

    /** Bumped when the selected item's bounds change so the pill follows it (e.g. label collapse). */
    private var layoutTick by mutableIntStateOf(0)

    /** Registers a nav item (in composition order); the returned holder is filled by its layout. */
    fun newItemBounds(): NavItemBounds = NavItemBounds().also { items += it }

    private fun itemAt(x: Float): NavItemBounds? =
        items.firstOrNull { it.placed && x >= it.left && x < it.left + it.width }
            ?: items.filter { it.placed }.minByOrNull { abs(x - (it.left + it.width / 2f)) }

    /** Finger down at ([x], [y]) in the item row's coordinates (Android `JellyMotion.begin`). */
    fun onPress(x: Float, y: Float) {
        val hit = itemAt(x)
        pressed = true
        releasePending = false
        pressTarget = 1.0
        shapeTarget = 1.3
        if (hit != null && hit !== target) moveTo(hit)
        ensureRunning()
    }

    /** Finger up (Android `JellyMotion.finish`); the capsule settles on the committed selection. */
    fun onRelease() {
        pressed = false
        releasePending = true
        ensureRunning()
        scope.launch {
            // The tap's selection is committed by the next recomposition's side effect. If it
            // does not arrive (e.g. the profile long-press opened its popup), go back (Android
            // `JellyMotion.cancel`).
            repeat(3) { withFrameNanos { } }
            val selected = committed
            if (!pressed && selected != null && target !== selected) moveTo(selected)
        }
    }

    fun onItemPlaced(bounds: NavItemBounds, left: Float, top: Float, width: Float, height: Float) {
        if (bounds.placed && bounds.left == left && bounds.top == top &&
            bounds.width == width && bounds.height == height
        ) {
            return
        }
        bounds.placed = true
        bounds.left = left
        bounds.top = top
        bounds.width = width
        bounds.height = height
        ordered = items.filter { it.placed }.sortedBy { it.left }
        if (bounds === target) layoutTick++
    }

    fun onItemSelected(bounds: NavItemBounds) {
        // Called on every recomposition of the selected item: only a real change of the selection
        // moves the capsule (a repeat must not pull it back while a finger is down on another tab).
        val changed = committed !== bounds
        committed = bounds
        if (!changed || target === bounds) return
        moveTo(bounds)
        if (glassMode && !pressed) {
            // Selection without a touch (Android `JellyMotion.select`): no swell.
            releasePending = true
            pressTarget = 0.0
            shapeTarget = 1.0
            ensureRunning()
        }
    }

    private fun moveTo(bounds: NavItemBounds) {
        val previous = target
        if (previous != null && previous.placed && bounds.placed) {
            // Start from where the pill is currently drawn so an interrupted slide continues smoothly.
            val t = progress.value
            var left = previous.left
            var top = previous.top
            var width = previous.width
            var height = previous.height
            if (hasFrom) {
                left = fromLeft + (previous.left - fromLeft) * t
                top = fromTop + (previous.top - fromTop) * t
                width = fromWidth + (previous.width - fromWidth) * t
                height = fromHeight + (previous.height - fromHeight) * t
            }
            startSlide(bounds, left, top, width, height)
        } else {
            hasFrom = false
            target = bounds
            layoutTick++
        }
    }

    /** Slides the capsule from the given rect to [bounds] (the same spring as a tab switch). */
    private fun startSlide(bounds: NavItemBounds, left: Float, top: Float, width: Float, height: Float) {
        fromLeft = left
        fromTop = top
        fromWidth = width
        fromHeight = height
        hasFrom = true
        target = bounds
        val tabWidth = bounds.width.coerceAtLeast(1f)
        travelTabs = ((bounds.left + bounds.width / 2f) - (fromLeft + fromWidth / 2f)) / tabWidth
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            progress.snapTo(0f)
            progress.animateTo(
                targetValue = 1f,
                animationSpec = spring(
                    dampingRatio = 1f,
                    stiffness = 1000f,
                    visibilityThreshold = 0.001f,
                ),
            )
        }
    }

    fun holdsPopupAt(x: Float): Boolean = itemAt(x)?.holdsPopup == true

    /** Distance between two neighbouring tabs, in px. */
    private fun tabPitch(): Float =
        if (ordered.size > 1) (ordered[1].left - ordered[0].left).coerceAtLeast(1f) else 1f

    /** Where the capsule is drawn right now (centre x), whether sliding or resting. */
    private fun drawnCenterX(): Float {
        val current = target ?: return 0f
        val currentCenter = current.left + current.width / 2f
        if (!hasFrom) return currentCenter
        val fromCenter = fromLeft + fromWidth / 2f
        return fromCenter + (currentCenter - fromCenter) * progress.value
    }

    /** Fills the drag rect for the fractional tab index [position]; false when nothing is placed. */
    private fun rectAt(position: Float): Boolean {
        val list = ordered
        if (list.isEmpty()) return false
        val p = position.coerceIn(0f, (list.size - 1).toFloat())
        val i = floor(p).toInt().coerceAtMost(list.size - 1)
        val j = min(i + 1, list.size - 1)
        val f = p - i
        val a = list[i]
        val b = list[j]
        dragLeft = a.left + (b.left - a.left) * f
        dragTop = a.top + (b.top - a.top) * f
        dragWidth = a.width + (b.width - a.width) * f
        dragHeight = a.height + (b.height - a.height) * f
        return true
    }

    /** Called once the finger has moved past the touch slop; false when there is nothing to drag. */
    fun beginDrag(): Boolean {
        val list = ordered
        if (list.size < 2 || target == null) return false
        val pitch = tabPitch()
        val first = list[0]
        val position = ((drawnCenterX() - (first.left + first.width / 2f)) / pitch)
            .coerceIn(0f, (list.size - 1).toFloat())
        val hit = list.indexOf(target)
        dragStartIndex = if (hit >= 0) hit.toDouble() else position.toDouble()
        dragTarget = dragStartIndex
        dragSpring.value = position.toDouble()
        dragSpring.velocity = 0.0
        dragging = true
        dragIndex = position
        dragActive = true
        // Stop a slide that the touch-down started; the capsule is driven by the finger now.
        scope.launch(start = CoroutineStart.UNDISPATCHED) { progress.snapTo(1f) }
        ensureRunning()
        return true
    }

    /** Finger moved [dx] px from where it went down (Android `JellyMotion.drag`). */
    fun onDrag(dx: Float) {
        if (!dragging) return
        dragTarget = (dragStartIndex + dx / tabPitch()).coerceIn(0.0, (ordered.size - 1).toDouble())
    }

    /** Finger lifted: the capsule settles on the nearest tab, which is returned so it can be selected. */
    fun endDrag(): NavItemBounds? {
        if (!dragging) return null
        val list = ordered
        val chosen = list.getOrNull(floor(dragTarget + 0.5).toInt().coerceIn(0, list.size - 1))
        settleDrag(chosen)
        return chosen
    }

    /** The gesture was cancelled: go back to the selected tab. */
    fun abortDrag() {
        if (!dragging) return
        settleDrag(committed)
    }

    private fun settleDrag(to: NavItemBounds?) {
        dragging = false
        dragActive = false
        if (to == null || !rectAt(dragSpring.value.toFloat())) return
        startSlide(to, dragLeft, dragTop, dragWidth, dragHeight)
    }

    private fun ensureRunning() {
        if (loop?.isActive == true) return
        loop = scope.launch {
            var previous = withFrameNanos { it }
            while (true) {
                val now = withFrameNanos { it }
                advance((now - previous) / 1_000_000_000.0)
                previous = now
                if (!isRunning()) break
            }
        }
    }

    private fun isRunning(): Boolean =
        pressed || dragging || releasePending || !velocity.isAtRest(0.0) || !press.isAtRest(0.0) ||
            !shapeX.isAtRest(1.0) || !trackPress.isAtRest(1.0)

    /** Android `JellyMotion.advance` + `publish`, for the springs that are not the slide itself. */
    private fun advance(seconds: Double) {
        val delta = seconds.coerceIn(0.0, 0.064)
        val maxIndex = (items.size - 1).coerceAtLeast(0)
        // Position velocity in tab units per second, divided by maxIndex, only while the finger is down.
        if (dragging) {
            dragSpring.advance(dragTarget, delta)
            dragIndex = dragSpring.value.toFloat()
        }
        val indexVelocity = when {
            dragging -> dragSpring.velocity
            hasFrom -> progress.velocity.toDouble() * travelTabs
            else -> 0.0
        }
        velocity.advance(if (pressed && maxIndex > 0) indexVelocity / maxIndex else 0.0, delta)
        val remaining = if (hasFrom) abs(1f - progress.value) * abs(travelTabs) else 0f
        if (releasePending && remaining < maxOf(1, maxIndex) * 0.025) {
            releasePending = false
            pressTarget = 0.0
            shapeTarget = 1.0
        }
        press.advance(pressTarget, delta)
        shapeX.advance(shapeTarget, delta)
        trackPress.advance(if (pressed) 1.025 else 1.0, delta)
        val speed = velocity.value / 10
        pillScaleX = (shapeX.value / (1 - (speed * 0.75).coerceIn(-0.2, 0.2))).toFloat()
        pillSwell = (shapeX.value - 1.0).toFloat()
        contentScale = (1 + 0.2 * press.value).toFloat()
        trackScale = trackPress.value.toFloat()
    }

    /**
     * Scale of a nav item's content: [contentScale] weighted by how much the capsule covers the
     * item (Android scales the cells inside the pill). Read in the layer phase.
     */
    fun contentScaleFor(bounds: NavItemBounds): Float {
        val scale = contentScale
        if (scale == 1f) return 1f
        if (target == null) return 1f
        val center = if (dragActive && rectAt(dragIndex)) dragLeft + dragWidth / 2f else drawnCenterX()
        val itemCenter = bounds.left + bounds.width / 2f
        val coverage = (1f - abs(center - itemCenter) / bounds.width.coerceAtLeast(1f)).coerceIn(0f, 1f)
        return 1f + (scale - 1f) * coverage
    }

    fun DrawScope.drawSelectionPill(color: Color, glass: Boolean) {
        // Always read so the draw layer is observing all of them, whatever state the pill is in.
        layoutTick
        val t = progress.value
        val scaleX = pillScaleX
        val swell = pillSwell
        val current = target ?: return
        if (!current.placed) return
        if (!glass && !current.selected) return
        var left = current.left
        var top = current.top
        var width = current.width
        var height = current.height
        if (glass && dragActive && rectAt(dragIndex)) {
            left = dragLeft
            top = dragTop
            width = dragWidth
            height = dragHeight
        } else if (hasFrom) {
            left = fromLeft + (left - fromLeft) * t
            top = fromTop + (top - fromTop) * t
            width = fromWidth + (width - fromWidth) * t
            height = fromHeight + (height - fromHeight) * t
        }
        if (width <= 0f || height <= 0f) return
        val radius = min(width, height) / 2f
        if (glass) {
            // Android jellyPillPath: horizontally the item width times pillScaleX (swell plus the
            // velocity stretch); vertically the resting height plus the SAME outward growth in px
            // as each side (width * swell / 2), never squashed. Fully rounded ends.
            val grow = width * swell / 2f
            val drawWidth = width * scaleX
            val drawHeight = height + 2f * grow
            val cornerRadius = min(drawWidth, drawHeight) / 2f
            drawGlassSelectionPill(
                left = left + (width - drawWidth) / 2f,
                top = top - grow,
                width = drawWidth,
                height = drawHeight,
                cornerRadius = cornerRadius,
            )
            return
        }
        drawRoundRect(
            color = color,
            topLeft = Offset(left, top),
            size = Size(width, height),
            cornerRadius = CornerRadius(radius, radius),
        )
    }
}

/** The lighter glass capsule behind the selected tab: pale fill, top gloss and a lit rim. */
private fun DrawScope.drawGlassSelectionPill(
    left: Float,
    top: Float,
    width: Float,
    height: Float,
    cornerRadius: Float,
) {
    drawRoundRect(
        color = Color.White.copy(alpha = 0.14f),
        topLeft = Offset(left, top),
        size = Size(width, height),
        cornerRadius = CornerRadius(cornerRadius, cornerRadius),
    )
    drawRoundRect(
        brush = Brush.verticalGradient(
            colors = listOf(Color.White.copy(alpha = 0.16f), Color.Transparent),
            startY = top,
            endY = top + height,
        ),
        topLeft = Offset(left, top),
        size = Size(width, height),
        cornerRadius = CornerRadius(cornerRadius, cornerRadius),
    )
    val rimWidth = 0.75.dp.toPx()
    drawRoundRect(
        brush = Brush.linearGradient(
            colors = listOf(
                Color.White.copy(alpha = 0.50f),
                Color.White.copy(alpha = 0.06f),
                Color.White.copy(alpha = 0.24f),
            ),
            start = Offset(left, top),
            end = Offset(left + width, top + height),
        ),
        topLeft = Offset(left + rimWidth / 2f, top + rimWidth / 2f),
        size = Size(width - rimWidth, height - rimWidth),
        cornerRadius = CornerRadius((cornerRadius - rimWidth / 2f).coerceAtLeast(0f)),
        style = Stroke(rimWidth),
    )
}

interface NuvioNavigationBarScope {
    @Composable
    fun NavItem(
        selected: Boolean,
        onClick: () -> Unit,
        icon: ImageVector,
        contentDescription: String?,
        modifier: Modifier = Modifier,
        label: String? = null,
    )

    @Composable
    fun NavItem(
        selected: Boolean,
        onClick: () -> Unit,
        icon: DrawableResource,
        contentDescription: String?,
        modifier: Modifier = Modifier,
        label: String? = null,
    )

    @Composable
    fun NavItem(
        selected: Boolean,
        onClick: () -> Unit,
        modifier: Modifier = Modifier,
        label: String? = null,
        content: @Composable () -> Unit,
    )
}

private class NuvioNavigationBarScopeImpl(
    private val rowScope: androidx.compose.foundation.layout.RowScope,
    private val labelFraction: State<Float>,
    private val compactSize: Boolean,
    private val pillState: NavPillState,
    private val glass: Boolean,
) : NuvioNavigationBarScope {
    private val iconSize = if (compactSize) 24.dp else 28.dp
    private val itemVerticalPadding = if (compactSize) 4.dp else NuvioTokens.Space.s6

    @Composable
    override fun NavItem(
        selected: Boolean,
        onClick: () -> Unit,
        icon: ImageVector,
        contentDescription: String?,
        modifier: Modifier,
        label: String?,
    ) {
        val tokens = MaterialTheme.nuvio
        val palette = MaterialTheme.themePalette
        val iconColor = animateColorAsState(
            targetValue = if (selected) tokens.colors.accent else tokens.colors.textMuted,
            label = "nav_icon_color",
        )

        val itemBounds = remember { pillState.newItemBounds() }
        SideEffect {
            itemBounds.selected = selected
            itemBounds.onClick = onClick
            if (selected) pillState.onItemSelected(itemBounds)
        }

        with(rowScope) {
            Column(
                modifier = modifier
                    .weight(1f)
                    // Measured into plain holders for the sliding selection pill (no state, no recomposition).
                    .onPlaced { coordinates ->
                        val bounds = coordinates.boundsInParent()
                        pillState.onItemPlaced(itemBounds, bounds.left, bounds.top, bounds.width, bounds.height)
                    }
                    .then(
                        if (glass) {
                            // Android: the cells under the capsule swell with it (contentScale).
                            Modifier.graphicsLayer {
                                val contentScale = pillState.contentScaleFor(itemBounds)
                                scaleX = contentScale
                                scaleY = contentScale
                            }
                        } else {
                            Modifier
                        },
                    )
                    .clip(RoundedCornerShape(NuvioTokens.Radius.full))
                    .selectable(
                        selected = selected,
                        enabled = true,
                        role = Role.Tab,
                        onClick = onClick,
                    )
                    .padding(vertical = itemVerticalPadding),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    modifier = Modifier
                        .size(iconSize)
                        .then(if (selected) Modifier.gradientMask(palette.accentBrush()) else Modifier),
                    imageVector = icon,
                    contentDescription = contentDescription,
                    tint = if (selected) Color.White else iconColor.value,
                )
                NavItemLabel(label = label, labelFraction = labelFraction, iconColor = iconColor, selected = selected, compactSize = compactSize, glass = glass)
            }
        }
    }

    @Composable
    override fun NavItem(
        selected: Boolean,
        onClick: () -> Unit,
        icon: DrawableResource,
        contentDescription: String?,
        modifier: Modifier,
        label: String?,
    ) {
        val tokens = MaterialTheme.nuvio
        val palette = MaterialTheme.themePalette
        val iconColor = animateColorAsState(
            targetValue = if (selected) tokens.colors.accent else tokens.colors.textMuted,
            label = "nav_icon_color",
        )

        val itemBounds = remember { pillState.newItemBounds() }
        SideEffect {
            itemBounds.selected = selected
            itemBounds.onClick = onClick
            if (selected) pillState.onItemSelected(itemBounds)
        }

        with(rowScope) {
            Column(
                modifier = modifier
                    .weight(1f)
                    // Measured into plain holders for the sliding selection pill (no state, no recomposition).
                    .onPlaced { coordinates ->
                        val bounds = coordinates.boundsInParent()
                        pillState.onItemPlaced(itemBounds, bounds.left, bounds.top, bounds.width, bounds.height)
                    }
                    .then(
                        if (glass) {
                            // Android: the cells under the capsule swell with it (contentScale).
                            Modifier.graphicsLayer {
                                val contentScale = pillState.contentScaleFor(itemBounds)
                                scaleX = contentScale
                                scaleY = contentScale
                            }
                        } else {
                            Modifier
                        },
                    )
                    .clip(RoundedCornerShape(NuvioTokens.Radius.full))
                    .selectable(
                        selected = selected,
                        enabled = true,
                        role = Role.Tab,
                        onClick = onClick,
                    )
                    .padding(vertical = itemVerticalPadding),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    modifier = Modifier
                        .size(iconSize)
                        .then(if (selected) Modifier.gradientMask(palette.accentBrush()) else Modifier),
                    painter = painterResource(icon),
                    contentDescription = contentDescription,
                    tint = if (selected) Color.White else iconColor.value,
                )
                NavItemLabel(label = label, labelFraction = labelFraction, iconColor = iconColor, selected = selected, compactSize = compactSize, glass = glass)
            }
        }
    }

    @Composable
    override fun NavItem(
        selected: Boolean,
        onClick: () -> Unit,
        modifier: Modifier,
        label: String?,
        content: @Composable () -> Unit,
    ) {
        val tokens = MaterialTheme.nuvio
        val iconColor = animateColorAsState(
            targetValue = if (selected) tokens.colors.accent else tokens.colors.textMuted,
            label = "nav_icon_color",
        )

        val itemBounds = remember { pillState.newItemBounds() }
        SideEffect {
            itemBounds.selected = selected
            itemBounds.onClick = onClick
            itemBounds.holdsPopup = true
            if (selected) pillState.onItemSelected(itemBounds)
        }

        with(rowScope) {
            Column(
                modifier = modifier
                    .weight(1f)
                    // Measured into plain holders for the sliding selection pill (no state, no recomposition).
                    .onPlaced { coordinates ->
                        val bounds = coordinates.boundsInParent()
                        pillState.onItemPlaced(itemBounds, bounds.left, bounds.top, bounds.width, bounds.height)
                    }
                    .then(
                        if (glass) {
                            // Android: the cells under the capsule swell with it (contentScale).
                            Modifier.graphicsLayer {
                                val contentScale = pillState.contentScaleFor(itemBounds)
                                scaleX = contentScale
                                scaleY = contentScale
                            }
                        } else {
                            Modifier
                        },
                    )
                    .clip(RoundedCornerShape(NuvioTokens.Radius.full))
                    .selectable(
                        selected = selected,
                        enabled = true,
                        role = Role.Tab,
                        onClick = onClick,
                    )
                    .padding(vertical = itemVerticalPadding),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (compactSize) {
                    Box(Modifier.size(iconSize), contentAlignment = Alignment.Center) { content() }
                } else {
                    content()
                }
                NavItemLabel(label = label, labelFraction = labelFraction, iconColor = iconColor, selected = selected, compactSize = compactSize, glass = glass)
            }
        }
    }
}

@Composable
private fun NavItemLabel(
    label: String?,
    labelFraction: State<Float>,
    iconColor: State<Color>,
    selected: Boolean,
    compactSize: Boolean,
    glass: Boolean,
) {
    if (label == null) return
    val visible by remember(labelFraction) { derivedStateOf { labelFraction.value > 0f } }
    if (!visible) return
    val gap = if (compactSize) 2.dp else NuvioTokens.Space.s3
    // The label grows and fades in the layout/draw phases, so the animation does not recompose.
    Box(
        modifier = Modifier
            .layout { measurable, constraints ->
                val fraction = labelFraction.value
                val gapPx = (gap * fraction).roundToPx()
                val heightPx = (NuvioTokens.Space.s14 * fraction).roundToPx()
                val placeable = measurable.measure(
                    constraints.copy(minHeight = 0, maxHeight = heightPx),
                )
                layout(placeable.width, gapPx + heightPx) {
                    placeable.placeRelative(0, gapPx)
                }
            }
            // Glass: the labels fade out ahead of the collapse and fade in late on expand, as in
            // the system bar.
            .graphicsLayer { alpha = if (glass) labelFraction.value * labelFraction.value else labelFraction.value },
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = NuvioTokens.Type.labelXs,
                lineHeight = NuvioTokens.LineHeight.labelXs,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            ),
            color = iconColor.value,
            maxLines = 1,
            overflow = TextOverflow.Clip,
        )
    }
}


/**
 * Classic flat navigation bar — the original pre-pill implementation.
 * No floating pill, no labels, no scroll behavior. Simple icon row with a top divider.
 */
@Composable
fun NuvioClassicNavigationBar(
    modifier: Modifier = Modifier,
    content: @Composable NuvioNavigationBarScope.() -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    Column(modifier.fillMaxWidth()) {
        androidx.compose.material3.HorizontalDivider(
            thickness = NuvioTokens.Space.hairline,
            color = tokens.colors.borderDefault,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(nuvioBottomNavigationBarInsets().asPaddingValues())
                .padding(horizontal = NuvioTokens.Space.s4, vertical = nuvioBottomNavigationExtraVerticalPadding),
            horizontalArrangement = Arrangement.spacedBy(tokens.spacing.controlGap, Alignment.CenterHorizontally),
        ) {
            NuvioClassicNavigationBarScopeImpl(this).content()
        }
    }
}

private class NuvioClassicNavigationBarScopeImpl(
    private val rowScope: androidx.compose.foundation.layout.RowScope,
) : NuvioNavigationBarScope {

    @Composable
    override fun NavItem(
        selected: Boolean,
        onClick: () -> Unit,
        icon: ImageVector,
        contentDescription: String?,
        modifier: Modifier,
        label: String?,
    ) {
        val tokens = MaterialTheme.nuvio
        val palette = MaterialTheme.themePalette
        val iconColor by animateColorAsState(
            targetValue = if (selected) tokens.colors.accent else tokens.colors.textMuted,
            label = "classic_nav_icon_color",
        )
        with(rowScope) {
            Icon(
                modifier = modifier
                    .widthIn(max = tokens.components.navItemMaxWidth)
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .clip(tokens.components.navItemShape)
                    .selectable(
                        selected = selected,
                        enabled = true,
                        role = Role.Tab,
                        onClick = onClick,
                    )
                    .padding(NuvioTokens.Space.s10)
                    .size(tokens.components.navIconSize)
                    .then(if (selected) Modifier.gradientMask(palette.accentBrush()) else Modifier),
                imageVector = icon,
                contentDescription = contentDescription,
                tint = if (selected) Color.White else iconColor,
            )
        }
    }

    @Composable
    override fun NavItem(
        selected: Boolean,
        onClick: () -> Unit,
        icon: DrawableResource,
        contentDescription: String?,
        modifier: Modifier,
        label: String?,
    ) {
        val tokens = MaterialTheme.nuvio
        val palette = MaterialTheme.themePalette
        val iconColor by animateColorAsState(
            targetValue = if (selected) tokens.colors.accent else tokens.colors.textMuted,
            label = "classic_nav_icon_color",
        )
        with(rowScope) {
            Icon(
                modifier = modifier
                    .widthIn(max = tokens.components.navItemMaxWidth)
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .clip(tokens.components.navItemShape)
                    .selectable(
                        selected = selected,
                        enabled = true,
                        role = Role.Tab,
                        onClick = onClick,
                    )
                    .padding(NuvioTokens.Space.s10)
                    .size(tokens.components.navIconSize)
                    .then(if (selected) Modifier.gradientMask(palette.accentBrush()) else Modifier),
                painter = painterResource(icon),
                contentDescription = contentDescription,
                tint = if (selected) Color.White else iconColor,
            )
        }
    }

    @Composable
    override fun NavItem(
        selected: Boolean,
        onClick: () -> Unit,
        modifier: Modifier,
        label: String?,
        content: @Composable () -> Unit,
    ) {
        val tokens = MaterialTheme.nuvio
        with(rowScope) {
            Box(
                modifier = modifier
                    .widthIn(max = tokens.components.navItemMaxWidth)
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .clip(tokens.components.navItemShape)
                    .selectable(
                        selected = selected,
                        enabled = true,
                        role = Role.Tab,
                        onClick = onClick,
                    )
                    .padding(NuvioTokens.Space.s10),
                contentAlignment = Alignment.Center,
            ) {
                content()
            }
        }
    }
}
