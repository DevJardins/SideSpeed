package io.github.devjardins.sidespeed.companion.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier

enum class Page { HOME, ADD, SETTINGS, ABOUT }

/** Root of the companion UI, shared by the Android and desktop apps. */
@Composable
fun CompanionApp(vm: CompanionViewModel, desktop: Boolean, aboutText: String = "SideSpeed Companion · GPL-3.0") {
    val state by vm.state.collectAsState()
    var page by remember { mutableStateOf(Page.HOME) }
    var dismissedFirstRun by remember { mutableStateOf(false) }
    androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().background(Ss.background)) {
        when {
            !state.loaded -> Unit
            state.firstRun && !dismissedFirstRun ->
                FirstRunScreen(state.currentNetwork, { vm.trustCurrentNetwork("Trusted Network 1") }, { dismissedFirstRun = true })
            page == Page.HOME -> HomeScreen(state, { vm.restartNow(it) }, { page = Page.ADD }, { page = Page.SETTINGS })
            page == Page.ADD -> AddTvScreen(state, vm::findTvs, vm::addTvByAddress) { page = Page.HOME }
            page == Page.SETTINGS -> SettingsScreen(
                state, desktop, vm::updateSettings,
                { vm.trustCurrentNetwork("Trusted Network ${state.trustedNetworks.size + 1}") },
                vm::removeTrustedNetwork, { page = Page.ABOUT }, { page = Page.HOME },
            )
            else -> { SsText(aboutText, modifier = androidx.compose.ui.Modifier); }
        }
    }
}
