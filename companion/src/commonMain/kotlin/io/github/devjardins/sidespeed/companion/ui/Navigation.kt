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

enum class Page { HOME, ADD, SETTINGS, ABOUT, LANGUAGE, INSTALL }

/**
 * Where the user is in the app. Held outside [CompanionApp] so a language switch, which rebuilds
 * the app's content, keeps the page; saveable so an Android activity recreate keeps it too.
 */
class CompanionNav(page: Page = Page.HOME, dismissedFirstRun: Boolean = false, focusLanguage: Boolean = false) {
    var page by mutableStateOf(page)
    var dismissedFirstRun by mutableStateOf(dismissedFirstRun)
    var focusLanguage by mutableStateOf(focusLanguage)

    companion object {
        val Saver: Saver<CompanionNav, Any> = listSaver(
            save = { listOf(it.page.name, it.dismissedFirstRun, it.focusLanguage) },
            restore = {
                CompanionNav(
                    Page.valueOf(it[0] as String),
                    it[1] as Boolean,
                    it.getOrNull(2) as? Boolean ?: false,
                )
            },
        )
    }
}

/** A [CompanionNav] that survives configuration changes and activity recreation. */
@Composable
fun rememberCompanionNav(): CompanionNav = rememberSaveable(saver = CompanionNav.Saver) { CompanionNav() }

/** The page one level up, where Back goes; Home has none, so Back there leaves the app. */
fun Page.up(): Page? = when (this) {
    Page.HOME -> null
    Page.ADD, Page.SETTINGS, Page.INSTALL -> Page.HOME
    Page.ABOUT, Page.LANGUAGE -> Page.SETTINGS
}

/**
 * Goes one page up, as the on-screen and system Back do. Coming back from Language puts focus
 * on the Language button, where the user left from.
 */
fun CompanionNav.goUp() {
    val target = page.up() ?: return
    if (page == Page.LANGUAGE) focusLanguage = true
    page = target
}

/** The screens in navigation order; a higher ordinal is "deeper", so it slides in from the right. */
private enum class Screen { LOADING, FIRST_RUN, HOME, ADD, SETTINGS, ABOUT, LANGUAGE, INSTALL }

/**
 * Root of the companion UI, shared by the Android and desktop apps.
 *
 * [systemBack] lets the platform route its own Back (Android's Back button or gesture) to the
 * page above: it is called with whether there is one and what to do. Desktop has no system Back.
 */
@Composable
fun CompanionApp(
    vm: CompanionViewModel,
    desktop: Boolean,
    aboutText: UiText = UiText.Res(Res.string.about_licence_text),
    aboutVersion: String = "",
    updateState: CompanionUpdateState = CompanionUpdateState.Idle,
    onCheckUpdates: () -> Unit = {},
    updateAction: @Composable () -> Unit = {},
    systemBack: @Composable (enabled: Boolean, onBack: () -> Unit) -> Unit = { _, _ -> },
    language: LanguageController? = null,
    nav: CompanionNav = rememberCompanionNav(),
    onPickInstallFile: (tvId: String) -> Unit = {},
    dropHighlightTvId: String? = null,
    dropHintVisible: Boolean = false,
    onCardBoundsChanged: (tvId: String, rect: CardBounds?) -> Unit = { _, _ -> },
) {
    val state by vm.state.collectAsState()
    var page by nav::page
    var dismissedFirstRun by nav::dismissedFirstRun
    var focusLanguage by nav::focusLanguage
    // A TV was added from the Add screen: go Home, where its card is the confirmation.
    LaunchedEffect(state.addedAddress) {
        if (state.addedAddress != null) {
            if (page == Page.ADD) page = Page.HOME
            vm.acknowledgeAdded()
        }
    }
    // An install owns the screen while it runs; Home returns when it ends.
    val installing = state.install != null
    LaunchedEffect(installing) {
        if (installing) page = Page.INSTALL else if (page == Page.INSTALL) page = Page.HOME
    }
    val screen = when {
        !state.loaded -> Screen.LOADING
        installing -> Screen.INSTALL
        page == Page.INSTALL -> Screen.HOME
        state.firstRun && !dismissedFirstRun -> Screen.FIRST_RUN
        page == Page.HOME -> Screen.HOME
        page == Page.ADD -> Screen.ADD
        page == Page.SETTINGS -> Screen.SETTINGS
        page == Page.LANGUAGE && language != null -> Screen.LANGUAGE
        page == Page.LANGUAGE -> Screen.SETTINGS
        else -> Screen.ABOUT
    }
    val up = page.up().takeIf { screen != Screen.LOADING && screen != Screen.FIRST_RUN }
    // On the install screen Back cancels while that is still allowed, and is swallowed once the TV is committing.
    val installPhase = state.install?.phase
    systemBack(up != null || installing) {
        if (installing) { if (installPhase?.canCancel == true) vm.cancelInstall() } else nav.goUp()
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
                    onInstall = onPickInstallFile,
                    onOptimize = vm::optimizeNow,
                    onDismissInstallNotice = vm::dismissInstallNotice,
                    dropHighlightTvId = dropHighlightTvId,
                    dropHintVisible = dropHintVisible,
                    onCardBoundsChanged = onCardBoundsChanged,
                )
                Screen.INSTALL -> state.install?.let {
                    InstallScreen(it, vm::setInstallKeepOptimized, vm::confirmInstall, vm::cancelInstall, vm::retryInstall)
                }
                Screen.ADD -> {
                    DisposableEffect(Unit) { onDispose { vm.onDispose() } }
                    AddTvScreen(
                        state, vm::findTvsIn, vm::addTvByAddress, vm::cancelFind, vm::keepScanning,
                        onBack = { nav.goUp() },
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
                    { page = Page.ABOUT }, { nav.goUp() },
                    language = language,
                    onLanguage = { page = Page.LANGUAGE },
                    focusLanguage = focusLanguage,
                    onConsumeLanguageFocus = { focusLanguage = false },
                )
                Screen.LANGUAGE -> {
                    val controller = language
                    // Unreachable: Settings shows the Language button only when a controller is set.
                    if (controller != null) {
                        LanguageScreen(
                            controller = controller,
                            onBack = { nav.goUp() },
                        )
                    }
                }
                Screen.ABOUT -> AboutScreen(
                    aboutText = aboutText,
                    versionName = aboutVersion,
                    update = updateState,
                    onCheckUpdates = onCheckUpdates,
                    onBack = { nav.goUp() },
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
