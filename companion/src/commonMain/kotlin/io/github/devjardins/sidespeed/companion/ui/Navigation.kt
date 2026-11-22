package io.github.devjardins.sidespeed.companion.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

enum class Page { HOME, ADD, SETTINGS, ABOUT }

/** The screens in navigation order; a higher ordinal is "deeper", so it slides in from the right. */
private enum class Screen { LOADING, FIRST_RUN, HOME, ADD, SETTINGS, ABOUT }

/** Root of the companion UI, shared by the Android and desktop apps. */
@Composable
fun CompanionApp(vm: CompanionViewModel, desktop: Boolean, aboutText: String = "SideSpeed Companion · GPL-3.0\n\nThe Android robot is reproduced or modified from work created and shared by Google and used according to terms described in the Creative Commons 3.0 Attribution License.") {
    val state by vm.state.collectAsState()
    var page by remember { mutableStateOf(Page.HOME) }
    var dismissedFirstRun by remember { mutableStateOf(false) }
    // A TV was added from the Add screen: go Home, where its card is the confirmation.
    LaunchedEffect(state.addedAddress) {
        if (state.addedAddress != null) {
            if (page == Page.ADD) page = Page.HOME
            vm.acknowledgeAdded()
        }
    }
    val screen = when {
        !state.loaded -> Screen.LOADING
        state.firstRun && !dismissedFirstRun -> Screen.FIRST_RUN
        page == Page.HOME -> Screen.HOME
        page == Page.ADD -> Screen.ADD
        page == Page.SETTINGS -> Screen.SETTINGS
        else -> Screen.ABOUT
    }
    Box(Modifier.fillMaxSize().background(Ss.background)) {
        AnimatedContent(
            targetState = screen,
            modifier = Modifier.windowInsetsPadding(WindowInsets.safeDrawing),
            transitionSpec = {
                val forward = targetState.ordinal > initialState.ordinal
                val dir = if (forward) 1 else -1
                (fadeIn(tween(200)) + slideInHorizontally(tween(220)) { dir * it / 14 }) togetherWith
                    (fadeOut(tween(120)) + slideOutHorizontally(tween(220)) { -dir * it / 14 })
            },
            label = "screen",
        ) { target ->
            when (target) {
                Screen.LOADING -> Unit
                Screen.FIRST_RUN ->
                    FirstRunScreen(
                        state.currentNetwork, state.tailnet,
                        { vm.trustCurrentNetwork("Trusted Network 1") }, { vm.trustCurrentOverlay("Tailnet 1") },
                        { dismissedFirstRun = true },
                    )
                Screen.HOME -> HomeScreen(
                    state, { vm.restartNow(it) }, { page = Page.ADD }, { page = Page.SETTINGS },
                    vm::removeTv, vm::acknowledgeRestart, onVisibleChange = vm::setHomeVisible,
                )
                Screen.ADD -> {
                    DisposableEffect(Unit) { onDispose { vm.onDispose() } }
                    AddTvScreen(state, vm::findTvsIn, vm::addTvByAddress, vm::cancelFind, vm::keepScanning) { page = Page.HOME }
                }
                Screen.SETTINGS -> SettingsScreen(
                    state, desktop, vm::updateSettings,
                    { vm.trustCurrentNetwork("Trusted Network ${state.trustedNetworks.size + 1}") },
                    vm::renameTrustedNetwork, vm::removeTrustedNetwork, vm::removeTrustedOverlay,
                    { page = Page.ABOUT }, { page = Page.HOME },
                )
                Screen.ABOUT -> Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Header("About and licences", onBack = { page = Page.SETTINGS })
                    SsCard { SsText(aboutText) }
                }
            }
        }
    }
}

/** Screen header: an optional back chevron, the title, and optional trailing content. */
@Composable
fun Header(title: String, onBack: (() -> Unit)? = null, trailing: @Composable () -> Unit = {}) {
    Row(Modifier.padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (onBack != null) SsIconButton(onBack, "Back") { SsGlyph(Glyph.BACK) }
        SsText(title, Ss.title, Modifier.weight(1f))
        trailing()
    }
}
