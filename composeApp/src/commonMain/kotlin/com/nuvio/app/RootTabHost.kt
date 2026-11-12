package com.nuvio.app

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import androidx.lifecycle.compose.rememberLifecycleOwner
import com.nuvio.app.core.ui.LocalScreenActive
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch

@Composable
internal fun RootTabHost(
    selectedTab: AppScreenTab,
    modifier: Modifier = Modifier,
    active: Boolean = true,
    profileId: Int? = null,
    content: @Composable (AppScreenTab) -> Unit,
) {
    val parentLifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val hostActive = active && parentLifecycleState.isAtLeast(Lifecycle.State.STARTED)
    val focusManager = LocalFocusManager.current
    DisposableEffect(selectedTab, hostActive, profileId) {
        onDispose { focusManager.clearFocus(force = true) }
    }

    key(profileId) {
        val tabStateHolder = rememberSaveableStateHolder()
        val visitedTabs = remember { mutableSetOf<AppScreenTab>() }
        val displayedTabs = remember(selectedTab) { (visitedTabs + selectedTab).toList() }
        // Panes created after the first one (first visit of a tab) fade in too; the initial tab does not.
        val animateNewPanes = visitedTabs.isNotEmpty()
        SideEffect { visitedTabs += selectedTab }

        Layout(
            modifier = modifier.fillMaxSize(),
            content = {
                displayedTabs.forEach { tab ->
                    key(tab) {
                        RootTabPane(
                            tab = tab,
                            active = hostActive && tab == selectedTab,
                            selected = tab == selectedTab,
                            fadeInOnFirstShow = animateNewPanes,
                            stateHolder = tabStateHolder,
                            content = content,
                        )
                    }
                }
            },
        ) { measurables, constraints ->
            val placeable = measurables[displayedTabs.indexOf(selectedTab)].measure(constraints)
            layout(placeable.width, placeable.height) {
                placeable.placeRelative(0, 0)
            }
        }
    }
}

@Composable
private fun RootTabPane(
    tab: AppScreenTab,
    active: Boolean,
    selected: Boolean,
    fadeInOnFirstShow: Boolean,
    stateHolder: SaveableStateHolder,
    content: @Composable (AppScreenTab) -> Unit,
) {
    // Short fade-in of the incoming page only (triggered by tab selection, not by the host going
    // inactive/active for overlays or backgrounding). Inactive panes are not measured or drawn, so no
    // second page is drawn during the fade; alpha is read in the layer (draw) phase, not in composition.
    val fade = remember { Animatable(if (fadeInOnFirstShow && selected) 0f else 1f) }
    val fadeScope = rememberCoroutineScope()
    val wasSelected = remember { TabPaneSelection(selected && !fadeInOnFirstShow) }
    SideEffect {
        if (selected && !wasSelected.value) {
            wasSelected.value = true
            fadeScope.launch(start = CoroutineStart.UNDISPATCHED) {
                fade.snapTo(0f)
                fade.animateTo(1f, tween(durationMillis = 120, easing = LinearOutSlowInEasing))
            }
        } else if (!selected) {
            wasSelected.value = false
        }
    }
    val lifecycleOwner = rememberLifecycleOwner(
        maxLifecycle = if (active) Lifecycle.State.RESUMED else Lifecycle.State.CREATED,
    )
    CompositionLocalProvider(
        LocalLifecycleOwner provides lifecycleOwner,
        LocalScreenActive provides active,
    ) {
        stateHolder.SaveableStateProvider(tab.name) {
            Box(
                Modifier.fillMaxSize()
                    .graphicsLayer {
                        alpha = fade.value
                        compositingStrategy = CompositingStrategy.ModulateAlpha
                    }
                    .focusProperties { canFocus = active }
                    .pointerInput(active) {
                        if (!active) {
                            awaitPointerEventScope {
                                while (true) {
                                    awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                                }
                            }
                        }
                    }
                    .then(if (active) Modifier else Modifier.clearAndSetSemantics {}),
            ) {
                content(tab)
            }
        }
    }
}

private class TabPaneSelection(var value: Boolean)
