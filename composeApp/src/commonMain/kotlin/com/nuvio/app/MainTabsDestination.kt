package com.nuvio.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.LocalNuvioBottomNavigationOverlayPadding
import com.nuvio.app.core.ui.LocalNuvioGlassNavigationBar
import com.nuvio.app.core.ui.LocalNuvioNavBarScrollState
import com.nuvio.app.core.ui.NavBarGlassWarmUpGate
import com.nuvio.app.core.ui.NavBarGlassWarmUpHost
import com.nuvio.app.core.ui.NuvioNavBarScrollState
import com.nuvio.app.core.ui.NuvioClassicNavigationBar
import com.nuvio.app.core.ui.FloatingNavigationBar
import com.nuvio.app.core.ui.FloatingNavigationItem
import com.nuvio.app.core.ui.PlatformBackHandler
import com.nuvio.app.core.ui.rememberNuvioNavBarScrollState
import com.nuvio.app.features.profiles.NuvioProfile
import com.nuvio.app.features.profiles.ProfileSwitcherTab
import com.nuvio.app.features.settings.NavBarStyle
import com.nuvio.app.features.settings.ThemeSettingsRepository
import dev.chrisbanes.haze.hazeSource
import kotlinx.coroutines.delay
import dev.chrisbanes.haze.rememberHazeState
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.compose_nav_home
import nuvio.composeapp.generated.resources.compose_nav_library
import nuvio.composeapp.generated.resources.compose_nav_profile
import nuvio.composeapp.generated.resources.compose_nav_search
import nuvio.composeapp.generated.resources.sidebar_library
import nuvio.composeapp.generated.resources.sidebar_search
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun MainTabsDestination(
    selectedTab: AppScreenTab,
    initialHomeReady: Boolean,
    rootRouteActive: Boolean,
    useTabletFloatingTabBar: Boolean,
    useNativeNavigation: Boolean,
    useNativeTabBar: Boolean,
    liquidGlassNativeTabBarSupported: Boolean,
    liquidGlassNativeTabBarEnabled: Boolean,
    requests: AppTabRequests,
    state: AppTabState,
    actions: (isTabletLayout: Boolean) -> AppTabActions,
    onBack: () -> Unit,
    onTabSelected: (AppScreenTab) -> Unit,
    onProfileSelected: (NuvioProfile) -> Unit,
    onAddProfileRequested: () -> Unit,
) {
    PlatformBackHandler(enabled = rootRouteActive, onBack = onBack)

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val isTabletLayout = useTabletFloatingTabBar || maxWidth >= 768.dp
        // iOS < 16 cannot host the SwiftUI shell (and iOS < 26 has no Liquid Glass), so the Compose
        // shell draws the bar. There the bottom floating bar is used on tablets too, like the
        // bottom bar of the native iOS 26 phone shell; the top bar stays for Android tablets and
        // for the native iPad shell.
        val iosComposeShell = isIos && !useNativeNavigation
        val bottomBarOnTablet = iosComposeShell && isTabletLayout
        val topBarOnTablet = isTabletLayout && !bottomBarOnTablet
        val tabActions = remember(actions, isTabletLayout) { actions(isTabletLayout) }
        val useNativeBottomTabs = if (useNativeNavigation) {
            useNativeTabBar
        } else {
            liquidGlassNativeTabBarSupported && liquidGlassNativeTabBarEnabled && initialHomeReady
        }
        val tabsRouteActive = rootRouteActive
        val navBarScrollState = rememberNuvioNavBarScrollState()
        val density = LocalDensity.current
        val imeInsets = WindowInsets.ime
        val keyboardVisible by remember(imeInsets, density) {
            derivedStateOf { imeInsets.getBottom(density) > 0 }
        }
        val navBarHazeState = rememberHazeState()
        val storedNavBarStyle by remember { ThemeSettingsRepository.navBarStyle }.collectAsStateWithLifecycle()
        // The classic flat bar is not offered on iPad (the setting is hidden on iOS); tablets use
        // the floating bar.
        val navBarStyleSetting =
            if (bottomBarOnTablet && storedNavBarStyle == NavBarStyle.CLASSIC) NavBarStyle.ADAPTIVE else storedNavBarStyle
        val showBottomFloatingBar =
            !useNativeBottomTabs && (!isTabletLayout || bottomBarOnTablet) && navBarStyleSetting != NavBarStyle.CLASSIC
        val navBarGlowEnabled by ThemeSettingsRepository.navBarGlowEnabled.collectAsStateWithLifecycle()
        // Compact <-> expanded trigger of the iOS Compose shell bar: it collapses while scrolling
        // down and expands when scrolling up or when the content is back at its start. Selecting a
        // tab leaves the bar as it is (a compact bar stays compact, an expanded one stays expanded).
        SideEffect { navBarScrollState.expandsAtTop = iosComposeShell }
        val floatingNavigationItems = listOf(
            FloatingNavigationItem(
                selected = selectedTab == AppScreenTab.Home,
                onClick = { onTabSelected(AppScreenTab.Home) },
                icon = Icons.Filled.Home,
                label = stringResource(Res.string.compose_nav_home),
            ),
            FloatingNavigationItem(
                selected = selectedTab == AppScreenTab.Search,
                onClick = { onTabSelected(AppScreenTab.Search) },
                drawable = Res.drawable.sidebar_search,
                label = stringResource(Res.string.compose_nav_search),
            ),
            FloatingNavigationItem(
                selected = selectedTab == AppScreenTab.Library,
                onClick = { onTabSelected(AppScreenTab.Library) },
                drawable = Res.drawable.sidebar_library,
                label = stringResource(Res.string.compose_nav_library),
            ),
            FloatingNavigationItem(
                selected = selectedTab == AppScreenTab.Settings,
                onClick = { onTabSelected(AppScreenTab.Settings) },
                label = stringResource(Res.string.compose_nav_profile),
                content = { onClick ->
                    ProfileSwitcherTab(
                        selected = selectedTab == AppScreenTab.Settings,
                        onClick = onClick,
                        onProfileSelected = onProfileSelected,
                        onAddProfileRequested = onAddProfileRequested,
                        hazeState = navBarHazeState,
                        popupBelowAnchor = topBarOnTablet,
                    )
                },
            ),
        )

        // One-off, invisible run of the glass bar through its states (see NavBarGlassWarmUp), a
        // little after the first screen is ready.
        var navBarWarmUpRunning by remember { mutableStateOf(false) }
        val navBarWarmUpEligible = iosComposeShell && showBottomFloatingBar && initialHomeReady && rootRouteActive
        LaunchedEffect(navBarWarmUpEligible) {
            if (!navBarWarmUpEligible || NavBarGlassWarmUpGate.completed) return@LaunchedEffect
            delay(2_000)
            if (NavBarGlassWarmUpGate.completed) return@LaunchedEffect
            NavBarGlassWarmUpGate.completed = true
            navBarWarmUpRunning = true
        }

        Scaffold(
            modifier = Modifier
                .fillMaxSize()
                .alpha(if (initialHomeReady) 1f else 0f),
            containerColor = Color.Transparent,
            contentWindowInsets = WindowInsets(0),
            bottomBar = {
                if (!isTabletLayout && !useNativeBottomTabs && navBarStyleSetting == NavBarStyle.CLASSIC) {
                    NuvioClassicNavigationBar {
                        NavItem(
                            selected = selectedTab == AppScreenTab.Home,
                            onClick = { onTabSelected(AppScreenTab.Home) },
                            icon = Icons.Filled.Home,
                            contentDescription = stringResource(Res.string.compose_nav_home),
                        )
                        NavItem(
                            selected = selectedTab == AppScreenTab.Search,
                            onClick = { onTabSelected(AppScreenTab.Search) },
                            icon = Res.drawable.sidebar_search,
                            contentDescription = stringResource(Res.string.compose_nav_search),
                        )
                        NavItem(
                            selected = selectedTab == AppScreenTab.Library,
                            onClick = { onTabSelected(AppScreenTab.Library) },
                            icon = Res.drawable.sidebar_library,
                            contentDescription = stringResource(Res.string.compose_nav_library),
                        )
                        NavItem(
                            selected = selectedTab == AppScreenTab.Settings,
                            onClick = { onTabSelected(AppScreenTab.Settings) },
                        ) {
                            ProfileSwitcherTab(
                                selected = selectedTab == AppScreenTab.Settings,
                                onClick = { onTabSelected(AppScreenTab.Settings) },
                                onProfileSelected = onProfileSelected,
                                onAddProfileRequested = onAddProfileRequested,
                            )
                        }
                    }
                }
            },
        ) { innerPadding ->
            Box(modifier = Modifier.fillMaxSize()) {
                // Below the page content, which is opaque, so it is never seen.
                NavBarGlassWarmUpHost(
                    running = { navBarWarmUpRunning && rootRouteActive },
                    hazeState = navBarHazeState,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .then(if (bottomBarOnTablet) Modifier.widthIn(max = 416.dp) else Modifier),
                    onFinished = { navBarWarmUpRunning = false },
                )
                CompositionLocalProvider(
                    LocalNuvioBottomNavigationOverlayPadding provides if (useNativeBottomTabs) 49.dp else if (showBottomFloatingBar) 72.dp else 0.dp,
                    LocalNuvioNavBarScrollState provides navBarScrollState,
                ) {
                    AppTabHost(
                        selectedTab = selectedTab,
                        requests = requests,
                        state = state,
                        actions = tabActions,
                        modifier = Modifier
                            .fillMaxSize()
                            .then(if (isTabletLayout || navBarStyleSetting != NavBarStyle.CLASSIC) Modifier.hazeSource(state = navBarHazeState) else Modifier)
                            .then(if (navBarStyleSetting == NavBarStyle.ADAPTIVE) Modifier.nestedScroll(navBarScrollState.nestedScrollConnection) else Modifier)
                            .padding(innerPadding),
                    )
                }

                if (topBarOnTablet && !useNativeBottomTabs) {
                    val tabletNavBarScrollState = remember { NuvioNavBarScrollState().apply { collapse() } }
                    FloatingNavigationBar(
                        modifier = Modifier.align(Alignment.TopCenter).widthIn(max = 416.dp),
                        scrollState = tabletNavBarScrollState,
                        hazeState = navBarHazeState,
                        contentPadding = PaddingValues(
                            top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 10.dp,
                            bottom = 8.dp,
                        ),
                        compactSize = true,
                        items = floatingNavigationItems,
                        glowEnabled = navBarGlowEnabled,
                    )
                }

                if (showBottomFloatingBar) {
                    when (navBarStyleSetting) {
                        NavBarStyle.EXPANDED -> navBarScrollState.expand()
                        NavBarStyle.COMPACT -> navBarScrollState.collapse()
                        else -> {}
                    }
                    // The native iOS tab bar sits under the keyboard; here the bar slides away while
                    // the keyboard is up instead of floating over the keyboard and the search results.
                    AnimatedVisibility(
                        visible = !(iosComposeShell && keyboardVisible),
                        modifier = Modifier.align(Alignment.BottomCenter),
                        enter = fadeIn(tween(160)) + slideInVertically(tween(220)) { it / 2 },
                        exit = fadeOut(tween(120)) + slideOutVertically(tween(160)) { it / 2 },
                    ) {
                        CompositionLocalProvider(LocalNuvioGlassNavigationBar provides iosComposeShell) {
                            FloatingNavigationBar(
                                modifier = if (bottomBarOnTablet) Modifier.widthIn(max = 416.dp) else Modifier,
                                scrollState = navBarScrollState,
                                hazeState = navBarHazeState,
                                items = floatingNavigationItems,
                                glowEnabled = navBarGlowEnabled,
                            )
                        }
                    }
                }
            }
        }
    }
}
