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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.github.devjardins.sidespeed.companion.resources.Res
import io.github.devjardins.sidespeed.companion.resources.about_licence_text
import io.github.devjardins.sidespeed.companion.resources.common_back
import io.github.devjardins.sidespeed.companion.l10n.LanguageController
import io.github.devjardins.sidespeed.companion.ui.text.UiText
import io.github.devjardins.sidespeed.companion.update.CompanionUpdateState
import org.jetbrains.compose.resources.stringResource

enum class Page { HOME, ADD, SETTINGS, ABOUT }

/**
 * Where the user is in the app. Held outside [CompanionApp] so a language switch, which rebuilds
 * the app's content, keeps the page; saveable so an Android activity recreate keeps it too.
 */
class CompanionNav(page: Page = Page.HOME, dismissedFirstRun: Boolean = false) {
    var page by mutableStateOf(page)
    var dismissedFirstRun by mutableStateOf(dismissedFirstRun)

    companion object {
        val Saver: Saver<CompanionNav, Any> = listSaver(
            save = { listOf(it.page.name, it.dismissedFirstRun) },
            restore = { CompanionNav(Page.valueOf(it[0] as String), it[1] as Boolean) },
        )
    }
}

/** A [CompanionNav] that survives configuration changes and activity recreation. */
@Composable
fun rememberCompanionNav(): CompanionNav = rememberSaveable(saver = CompanionNav.Saver) { CompanionNav() }

/** The screens in navigation order; a higher ordinal is "deeper", so it slides in from the right. */
private enum class Screen { LOADING, FIRST_RUN, HOME, ADD, SETTINGS, ABOUT }

/** Root of the companion UI, shared by the Android and desktop apps. */
@Composable
fun CompanionApp(
    vm: CompanionViewModel,
    desktop: Boolean,
    aboutText: UiText = UiText.Res(Res.string.about_licence_text),
    aboutVersion: String = "",
    updateState: CompanionUpdateState = CompanionUpdateState.Idle,
    onCheckUpdates: () -> Unit = {},
    updateAction: @Composable () -> Unit = {},
    language: LanguageController? = null,
    nav: CompanionNav = rememberCompanionNav(),
) {
    val state by vm.state.collectAsState()
    var page by nav::page
    var dismissedFirstRun by nav::dismissedFirstRun
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
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Box(Modifier.fillMaxSize().background(Ss.background)) {
        AnimatedContent(
            targetState = screen,
            modifier = Modifier.windowInsetsPadding(WindowInsets.safeDrawing),
            transitionSpec = {
                val forward = targetState.ordinal > initialState.ordinal
                val sign = if (rtl) -1 else 1
                val dir = (if (forward) 1 else -1) * sign
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
                        { vm.trustCurrentNetwork("") }, { vm.trustCurrentOverlay("") },
                        { dismissedFirstRun = true },
                    )
                Screen.HOME -> HomeScreen(
                    state, { vm.restartNow(it) }, { page = Page.ADD }, { page = Page.SETTINGS },
                    vm::removeTv, vm::acknowledgeRestart, onVisibleChange = vm::setHomeVisible,
                    onAcknowledge = vm::acknowledgeClassic,
                    onPair = vm::pairSaved,
                    onPort = vm::useTypedPort,
                    onSubmitPair = vm::submitPair,
                    onSubmitConnectPort = vm::submitConnectPort,
                    onCancelPair = vm::cancelPairing,
                    onConfirm = vm::confirmTv,
                )
                Screen.ADD -> {
                    DisposableEffect(Unit) { onDispose { vm.onDispose() } }
                    AddTvScreen(
                        state, vm::findTvsIn, vm::addTvByAddress, vm::cancelFind, vm::keepScanning,
                        onBack = { page = Page.HOME },
                        onClassicCheck = vm::setClassicChecked,
                        onStartPair = { vm.startPairing(it) },
                        onSubmitPair = vm::submitPair,
                        onSubmitConnectPort = vm::submitConnectPort,
                        onCancelPair = vm::cancelPairing,
                    )
                }
                Screen.SETTINGS -> SettingsScreen(
                    state, desktop, vm::updateSettings,
                    { vm.trustCurrentNetwork("") },
                    vm::renameTrustedNetwork, vm::removeTrustedNetwork, vm::removeTrustedOverlay,
                    { page = Page.ABOUT }, { page = Page.HOME },
                )
                Screen.ABOUT -> AboutScreen(
                    aboutText = aboutText,
                    versionName = aboutVersion,
                    update = updateState,
                    onCheckUpdates = onCheckUpdates,
                    onBack = { page = Page.SETTINGS },
                    updateAction = updateAction,
                )
            }
        }
    }
}

/** Screen header: an optional back chevron, the title, and optional trailing content. */
@Composable
fun Header(title: String, onBack: (() -> Unit)? = null, trailing: @Composable () -> Unit = {}) {
    Row(Modifier.padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (onBack != null) SsIconButton(onBack, stringResource(Res.string.common_back)) { SsGlyph(Glyph.BACK) }
        SsText(title, Ss.title, Modifier.weight(1f))
        trailing()
    }
}
