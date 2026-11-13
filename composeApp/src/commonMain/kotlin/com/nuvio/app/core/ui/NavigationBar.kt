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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.offset
import dev.chrisbanes.haze.HazeInputScale
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource
import kotlin.math.abs
import kotlin.math.min

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
 * Observes (never consumes) touches on the bar so the glass can react on touch-down. Runs in the
 * Initial/Final passes, so taps, the profile long-press and drags reach their own handlers.
 */
private fun Modifier.glassPressFeedback(pillState: NavPillState): Modifier = pointerInput(pillState) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        pillState.onPress(down.position.x, down.position.y)
        try {
            do {
                val event = awaitPointerEvent(PointerEventPass.Final)
            } while (event.changes.any { it.pressed })
        } finally {
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

/**
 * Soft drop shadow of the system bar: stacked translucent rounded rects drawn only outside the
 * pill outline (so the translucent glass is not darkened from behind). Draw phase only.
 */
private fun Modifier.glassBarShadow(): Modifier = drawBehind {
    val outline = Path().apply {
        addRoundRect(RoundRect(0f, 0f, size.width, size.height, CornerRadius(size.height / 2f)))
    }
    val offsetY = 6.dp.toPx()
    clipPath(outline, ClipOp.Difference) {
        for (layer in 1..8) {
            val spread = layer * 2.dp.toPx()
            drawRoundRect(
                color = Color.Black.copy(alpha = 0.03f),
                topLeft = Offset(-spread, -spread + offsetY),
                size = Size(size.width + 2f * spread, size.height + 2f * spread),
                cornerRadius = CornerRadius(size.height / 2f + spread),
            )
        }
    }
}

/**
 * Specular edge of the glass: a lit rim (bright at the top-left and, fainter, at the bottom-right),
 * a soft top gloss and a small highlight near the top-left corner. Cached per size.
 */
private fun Modifier.glassBarHighlights(): Modifier = drawWithCache {
    val rimWidth = 1.dp.toPx()
    val rim = Brush.linearGradient(
        colors = listOf(
            Color.White.copy(alpha = 0.55f),
            Color.White.copy(alpha = 0.07f),
            Color.White.copy(alpha = 0.05f),
            Color.White.copy(alpha = 0.28f),
        ),
        start = Offset.Zero,
        end = Offset(size.width, size.height),
    )
    val gloss = Brush.verticalGradient(
        listOf(Color.White.copy(alpha = 0.10f), Color.White.copy(alpha = 0.02f), Color.Transparent),
    )
    val corner = Brush.radialGradient(
        colors = listOf(Color.White.copy(alpha = 0.14f), Color.Transparent),
        center = Offset(size.height * 0.7f, 0f),
        radius = size.height * 1.5f,
    )
    onDrawBehind {
        drawRect(gloss)
        drawRect(corner)
        drawRoundRect(
            brush = rim,
            topLeft = Offset(rimWidth / 2f, rimWidth / 2f),
            size = Size(size.width - rimWidth, size.height - rimWidth),
            cornerRadius = CornerRadius((size.height - rimWidth) / 2f),
            style = Stroke(rimWidth),
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
    val selectedPillColor = MaterialTheme.nuvio.colors.accent.copy(alpha = NuvioTokens.Opacity.selected)

    // Dynamic horizontal padding: pill shrinks when labels are hidden — driven by same labelFraction.
    // Applied while measuring, so the animation does not recompose the bar and its items.
    val expandedHorizontalPadding = 28.dp
    val collapsedHorizontalPadding = 58.dp

    // Outer container — no background, just safe padding
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(contentPadding),
        contentAlignment = Alignment.BottomCenter,
    ) {
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
                    placeable.placeRelative(padding, 0)
                }
            }
            .fillMaxWidth()
            .then(
                if (glass) {
                    // Draw-phase only: reads the press spring when the layer is drawn.
                    Modifier.graphicsLayer {
                        val scale = 1f + 0.025f * pillState.pressFraction
                        scaleX = scale
                        scaleY = scale
                    }.then(Modifier.glassBarShadow())
                } else {
                    Modifier
                },
            )
            .clip(RoundedCornerShape(NuvioTokens.Radius.full))
            .then(
                if (hazeState != null) {
                    Modifier.hazeEffect(state = hazeState) {
                        blurRadius = if (glass) 30.dp else 24.dp
                        if (glass) {
                            inputScale = blurInputScale
                            noiseFactor = 0f
                        }
                    }
                } else {
                    Modifier
                },
            )
            .background(
                GlassBaseColor.copy(
                    alpha = when {
                        hazeState == null -> 0.82f
                        glass -> 0.42f
                        else -> 0.55f
                    },
                ),
            )

        Box(modifier = pillModifier) {
            if (glass) {
                Box(modifier = Modifier.matchParentSize().glassBarHighlights())
            }
            // Padded content box shared by the sliding selection pill and the item row, so both use
            // the same coordinate origin (item bounds are measured relative to the row).
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        horizontal = NuvioTokens.Space.s6,
                        vertical = NuvioTokens.Space.s4,
                    )
                    .then(if (glass) Modifier.glassPressFeedback(pillState) else Modifier),
            ) {
                // Own layer + draw-phase reads only: the slide invalidates this layer, never the
                // blurred pill background or the items, and never recomposes anything.
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .graphicsLayer()
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
}

/**
 * Drives the selection pill that slides from the previously selected item to the new one.
 * Same spring as the Android floating bar's position spring (critically damped, stiffness 1000).
 *
 * The slide is a single 0..1 [Animatable] interpolating from the rect the pill was drawn at when
 * the selection changed to the live bounds of the selected item. [progress] and [layoutTick] are
 * only read in the draw phase, so nothing recomposes per frame.
 */
private class NavPillState(private val scope: CoroutineScope) {
    private val progress = Animatable(1f)
    private val press = Animatable(0f)
    private var pressOnSelected = false
    private var travel = 0f
    private var target: NavItemBounds? = null
    private var hasFrom = false
    private var fromLeft = 0f
    private var fromTop = 0f
    private var fromWidth = 0f
    private var fromHeight = 0f

    /** 0 at rest, ~1 while a finger is down on the bar (a spring: may undershoot on release). */
    val pressFraction: Float get() = press.value

    /** Finger down at ([x], [y]) in the item row's coordinates. */
    fun onPress(x: Float, y: Float) {
        val current = target
        pressOnSelected = current != null && current.placed &&
            x >= current.left && x <= current.left + current.width &&
            y >= current.top && y <= current.top + current.height
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            press.animateTo(1f, spring(dampingRatio = 0.7f, stiffness = 700f))
        }
    }

    fun onRelease() {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            press.animateTo(0f, spring(dampingRatio = 0.5f, stiffness = 350f))
        }
    }

    /** Bumped when the selected item's bounds change so the pill follows it (e.g. label collapse). */
    private var layoutTick by mutableIntStateOf(0)

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
        if (bounds === target) layoutTick++
    }

    fun onItemSelected(bounds: NavItemBounds) {
        if (target === bounds) return
        val previous = target
        if (previous != null && previous.placed && bounds.placed) {
            // Start from where the pill is currently drawn so an interrupted slide continues smoothly.
            val t = progress.value
            if (hasFrom) {
                fromLeft += (previous.left - fromLeft) * t
                fromTop += (previous.top - fromTop) * t
                fromWidth += (previous.width - fromWidth) * t
                fromHeight += (previous.height - fromHeight) * t
            } else {
                fromLeft = previous.left
                fromTop = previous.top
                fromWidth = previous.width
                fromHeight = previous.height
            }
            hasFrom = true
            target = bounds
            travel = abs(bounds.left - fromLeft)
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
        } else {
            hasFrom = false
            target = bounds
            layoutTick++
        }
    }

    fun DrawScope.drawSelectionPill(color: Color, glass: Boolean) {
        // Always read so the draw layer is observing both, whatever state the pill is in.
        layoutTick
        val t = progress.value
        // Read before any early return so the layer keeps observing the press spring.
        val pressValue = press.value
        val current = target ?: return
        if (!current.selected || !current.placed) return
        var left = current.left
        var top = current.top
        var width = current.width
        var height = current.height
        if (hasFrom) {
            left = fromLeft + (left - fromLeft) * t
            top = fromTop + (top - fromTop) * t
            width = fromWidth + (width - fromWidth) * t
            height = fromHeight + (height - fromHeight) * t
        }
        if (width <= 0f || height <= 0f) return
        val radius = min(width, height) / 2f
        if (glass) {
            // Jelly feel: the capsule stretches along the slide and squashes a little, peaking
            // mid-way (spring progress 0..1), and grows slightly while pressed.
            var drawLeft = left
            var drawTop = top
            var drawWidth = width
            var drawHeight = height
            if (hasFrom) {
                val bump = (4f * t * (1f - t)).coerceAtLeast(0f)
                val stretch = bump * min(0.10f + 0.04f * (travel / width), 0.30f)
                drawWidth = width * (1f + stretch)
                drawHeight = height * (1f - 0.4f * stretch)
                drawLeft = left - (drawWidth - width) / 2f
                drawTop = top + (height - drawHeight) / 2f
            }
            if (pressOnSelected) {
                val grow = 1f + 0.09f * pressValue
                val grownWidth = drawWidth * grow
                val grownHeight = drawHeight * grow
                drawLeft -= (grownWidth - drawWidth) / 2f
                drawTop -= (grownHeight - drawHeight) / 2f
                drawWidth = grownWidth
                drawHeight = grownHeight
            }
            drawGlassSelectionPill(drawLeft, drawTop, drawWidth, drawHeight, lift = if (pressOnSelected) pressValue else 0f)
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
private fun DrawScope.drawGlassSelectionPill(left: Float, top: Float, width: Float, height: Float, lift: Float) {
    val radius = min(width, height) / 2f
    drawRoundRect(
        color = Color.White.copy(alpha = (0.14f + 0.08f * lift).coerceIn(0f, 1f)),
        topLeft = Offset(left, top),
        size = Size(width, height),
        cornerRadius = CornerRadius(radius, radius),
    )
    drawRoundRect(
        brush = Brush.verticalGradient(
            colors = listOf(Color.White.copy(alpha = 0.16f), Color.Transparent),
            startY = top,
            endY = top + height,
        ),
        topLeft = Offset(left, top),
        size = Size(width, height),
        cornerRadius = CornerRadius(radius, radius),
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
        cornerRadius = CornerRadius(radius - rimWidth / 2f),
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

        val itemBounds = remember { NavItemBounds() }
        SideEffect {
            itemBounds.selected = selected
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

        val itemBounds = remember { NavItemBounds() }
        SideEffect {
            itemBounds.selected = selected
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

        val itemBounds = remember { NavItemBounds() }
        SideEffect {
            itemBounds.selected = selected
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
