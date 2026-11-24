package io.github.devjardins.sidespeed.ui.setup

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import io.github.devjardins.sidespeed.ui.theme.bleed
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.tv.material3.Text
import io.github.devjardins.sidespeed.R
import io.github.devjardins.sidespeed.adb.DeviceKind
import io.github.devjardins.sidespeed.adb.PcStartCommands
import io.github.devjardins.sidespeed.adb.Readiness
import io.github.devjardins.sidespeed.adb.pcStartCommands
import io.github.devjardins.sidespeed.helper.HelperLaunch
import io.github.devjardins.sidespeed.ui.text.UiText
import io.github.devjardins.sidespeed.ui.text.resolve
import io.github.devjardins.sidespeed.ui.text.uiText
import io.github.devjardins.sidespeed.ui.theme.SsButton
import io.github.devjardins.sidespeed.ui.theme.SsCard
import io.github.devjardins.sidespeed.ui.theme.SsColors
import io.github.devjardins.sidespeed.ui.theme.SsRow
import io.github.devjardins.sidespeed.ui.theme.SsScreen
import io.github.devjardins.sidespeed.ui.theme.SsSpinner
import io.github.devjardins.sidespeed.ui.theme.SsType

@Composable
fun SetupScreen(vm: SetupViewModel, onReady: () -> Unit) {
    val state by vm.state.collectAsState()
    LaunchedEffect(state) { if (state == SetupUiState.Ready) onReady() }

    // Retry only while the screen is on show: start (or restart) on ON_START, stop on ON_STOP and on leaving.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> vm.start()
                Lifecycle.Event.ON_STOP -> vm.stopRetrying()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            vm.stopRetrying()
        }
    }

    SsScreen {
        // The scroll clips to the column bounds, which would cut the first/last
        // focusable's focus ring and scale: bleed the column out symmetrically and
        // pad the scroll content by the same amount, so resting edges stay aligned.
        val scrollInset = 8.dp
        Column(
            Modifier
                .fillMaxWidth()
                .bleed(vertical = scrollInset)
                .verticalScroll(rememberScrollState())
                .padding(vertical = scrollInset),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (val s = state) {
                SetupUiState.Loading, SetupUiState.Ready -> {
                    Text(stringResource(R.string.setup_headline), style = SsType.Headline, color = SsColors.TextPrimary)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        SsSpinner()
                        Text(stringResource(R.string.setup_checking), style = SsType.Body, color = SsColors.TextBody)
                    }
                }
                SetupUiState.NeedsWarningAck -> WarningContent(vm)
                is SetupUiState.Connecting -> if (vm.isFireOs) FireOsContent(vm, s) else GoogleTvContent(vm, s)
            }
        }
    }
}

@Composable
private fun WarningContent(vm: SetupViewModel) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Text(stringResource(R.string.setup_warning_title), style = SsType.Headline, color = SsColors.TextPrimary)
    SsCard(Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.setup_lan_warning), style = SsType.Body, color = SsColors.TextBody, modifier = Modifier.padding(24.dp))
    }
    SsButton(stringResource(R.string.setup_warning_ack), onClick = vm::acknowledgeWarning, modifier = Modifier.focusRequester(focus))
}

private enum class StepState { TODO, WAITING, WORKING, DONE }

/**
 * A step card. It is focusable (no action) so the D-pad can walk through the steps and the column scrolls
 * with focus: nothing on the setup screen may sit out of reach.
 */
@Composable
private fun StepCard(number: Int, title: String, state: StepState, statusText: String? = null, extra: @Composable () -> Unit = {}) {
    SsRow(onClick = {}, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(horizontal = 24.dp, vertical = 14.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Box(
                Modifier.size(32.dp).background(
                    if (state == StepState.DONE) SsColors.OkPillBg else SsColors.Button, CircleShape,
                ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (state == StepState.DONE) "✓" else number.toString(),
                    style = SsType.Body,
                    color = if (state == StepState.DONE) SsColors.Accent else SsColors.TextSecondary,
                )
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(title, style = SsType.Title, color = SsColors.TextPrimary)
                extra()
            }
            Row(
                Modifier.padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (state == StepState.WORKING) SsSpinner()
                statusText?.let {
                    Text(it, style = SsType.Caption, color = if (state == StepState.DONE) SsColors.Accent else SsColors.TextSecondary)
                }
            }
        }
    }
}

/** A focusable (no action) block of text, so long content below the last button can still be scrolled to. */
@Composable
private fun FocusableBlock(content: @Composable () -> Unit) {
    SsRow(onClick = {}, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 24.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            content()
        }
    }
}

@Composable
private fun Header(subtitle: String, readiness: Readiness?) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.setup_headline), style = SsType.Headline, color = SsColors.TextPrimary)
        Text(subtitle, style = SsType.Body, color = SsColors.TextSecondary)
        StatusCaption(readiness)
    }
}

@Composable
private fun GoogleTvContent(vm: SetupViewModel, s: SetupUiState.Connecting) {
    val context = LocalContext.current
    val settingsFocus = remember { FocusRequester() }
    val pairFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { settingsFocus.requestFocus() } }
    LaunchedEffect(s.refusedHint) { if (s.refusedHint && !s.showPairing) runCatching { pairFocus.requestFocus() } }
    var wasShowingPairing by remember { mutableStateOf(false) }
    LaunchedEffect(s.showPairing) {
        if (wasShowingPairing && !s.showPairing) runCatching { pairFocus.requestFocus() }
        wasShowingPairing = s.showPairing
    }

    // Buttons right under the headline, so the screen opens at the top with focus on them.
    Header(stringResource(R.string.setup_google_tv_subtitle), s.readiness)
    if (s.refusedHint && !s.showPairing) {
        Text(stringResource(R.string.setup_refused_hint), style = SsType.Body, color = SsColors.WarnPillText)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        SsButton(stringResource(R.string.setup_open_dev_settings), onClick = { openDevSettings(context) }, modifier = Modifier.focusRequester(settingsFocus))
        if (s.showPairing) {
            SsButton(stringResource(R.string.setup_hide_pairing), onClick = vm::hidePairing, modifier = Modifier.focusRequester(pairFocus))
        } else {
            SsButton(stringResource(R.string.setup_pair_with_code), onClick = vm::showPairing, modifier = Modifier.focusRequester(pairFocus))
        }
    }
    if (s.showPairing) PairingContent(vm, s)
    StepCard(
        1, stringResource(R.string.setup_step_wireless_debugging),
        if (s.detected) StepState.DONE else StepState.TODO,
        if (s.detected) stringResource(R.string.setup_step_detected) else null,
    ) { Text(stringResource(R.string.setup_step_wireless_debugging_path), style = SsType.Caption, color = SsColors.TextSecondary) }
    StepCard(
        2, stringResource(R.string.setup_step_allow_prompt),
        when {
            s.launching -> StepState.DONE
            s.waiting -> StepState.WAITING
            else -> StepState.TODO
        },
        if (!s.launching && s.waiting) stringResource(R.string.setup_step_waiting) else null,
    )
    StepCard(3, stringResource(R.string.setup_step_starts_helper), if (s.launching) StepState.WORKING else StepState.TODO)
}

@Composable
private fun PairingContent(vm: SetupViewModel, s: SetupUiState.Connecting) {
    var showPc by remember { mutableStateOf(false) }
    SsCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(stringResource(R.string.setup_pairing_title), style = SsType.Title, color = SsColors.TextPrimary)
            Text(
                stringResource(R.string.setup_pairing_instructions),
                style = SsType.Body, color = SsColors.TextBody,
            )
            var code by remember { mutableStateOf("") }
            var port by remember { mutableStateOf("") }
            Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.setup_pairing_code_label), style = SsType.Caption, color = SsColors.TextSecondary)
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                        PairingCodeGrid(code, 6, { code = it }, focusFirst = true)
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.setup_pairing_port_label), style = SsType.Caption, color = SsColors.TextSecondary)
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                        PairingCodeGrid(port, 5, { port = it })
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                SsButton(stringResource(R.string.setup_pairing_submit), onClick = { vm.pair(code, port) }, enabled = !s.pairing)
                SsButton(
                    stringResource(if (showPc) R.string.setup_pairing_hide_pc else R.string.setup_pairing_show_pc),
                    onClick = { showPc = !showPc },
                )
                if (s.pairing) SsSpinner()
                s.pairMessage?.let { Text(it.resolve(), style = SsType.Body, color = SsColors.TextBody) }
            }
        }
    }
    if (showPc) {
        FocusableBlock {
            Text(
                stringResource(R.string.setup_pc_google_tv_intro),
                style = SsType.Caption, color = SsColors.TextSecondary,
            )
            val cmds = pcStartCommands(DeviceKind.GOOGLE_TV, vm.wifiIp(), HelperLaunch.command(), killFirst = false)
            Commands(cmds.connect)
            Text(stringResource(R.string.setup_pc_enter_code_note), style = SsType.Caption, color = SsColors.TextSecondary)
            Commands(cmds.run)
            OtherWays(cmds)
        }
    }
}

@Composable
private fun FireOsContent(vm: SetupViewModel, s: SetupUiState.Connecting) {
    val context = LocalContext.current
    val settingsFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { settingsFocus.requestFocus() } }
    val stale = s.readiness == Readiness.HelperNeedsRestart
    val cmds = pcStartCommands(DeviceKind.FIRE_OS, vm.wifiIp(), HelperLaunch.command(), killFirst = stale)
    var showOther by remember { mutableStateOf(false) }

    Header(stringResource(R.string.setup_fire_os_subtitle), s.readiness)
    SsButton(stringResource(R.string.setup_open_dev_settings), onClick = { openDevSettings(context) }, modifier = Modifier.focusRequester(settingsFocus))
    StepCard(
        1, stringResource(R.string.setup_step_adb_debugging),
        if (s.detected) StepState.DONE else StepState.TODO,
        if (s.detected) stringResource(R.string.setup_step_detected) else null,
    ) { Text(stringResource(R.string.setup_step_adb_debugging_path), style = SsType.Caption, color = SsColors.TextSecondary) }
    StepCard(2, stringResource(R.string.setup_step_start_helper_companion), StepState.WAITING, stringResource(R.string.setup_step_waiting)) {
        if (stale) {
            Text(
                stringResource(R.string.setup_fire_os_stale_helper),
                style = SsType.Body, color = SsColors.WarnPillText,
            )
        }
        Text(
            stringResource(R.string.setup_fire_os_companion_hint),
            style = SsType.Body, color = SsColors.TextBody,
        )
    }
    SsButton(stringResource(if (showOther) R.string.setup_other_ways_hide else R.string.setup_other_ways_show), onClick = { showOther = !showOther })
    if (showOther) {
        FocusableBlock {
            Text(stringResource(R.string.setup_pc_fire_os_intro), style = SsType.Body, color = SsColors.TextBody)
            Commands(cmds.connect + cmds.run)
            OtherWays(cmds)
        }
    }
    StepCard(3, stringResource(R.string.setup_step_helper_starts), StepState.TODO, stringResource(R.string.setup_step_checks_automatically))
}

/** A quiet line with the last check's result, for troubleshooting. */
@Composable
private fun StatusCaption(r: Readiness?) {
    val text = when (r) {
        null, Readiness.HelperReady -> return
        Readiness.WaitingForAllowPrompt -> stringResource(R.string.setup_status_waiting_allow)
        Readiness.HelperNeedsRestart -> stringResource(R.string.setup_status_helper_outdated)
        is Readiness.NeedsHelperStart -> uiText(R.string.setup_status_last_check, r.reason).resolve()
    }
    Text(text, style = SsType.Caption, color = SsColors.TextSecondary)
}

/** Commands in mono at body size; long ones wrap. */
@Composable
private fun Commands(lines: List<String>) {
    val mono = SsType.Body.copy(fontFamily = FontFamily.Monospace)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        lines.forEach { Text(UiText.Ltr(it).resolve(), style = mono, color = SsColors.TextBody, softWrap = true) }
    }
}

/** The Windows note and the "adb shell, then paste" alternative. */
@Composable
private fun OtherWays(cmds: PcStartCommands) {
    Text(stringResource(R.string.setup_windows_note), style = SsType.Caption, color = SsColors.TextSecondary)
    Text(stringResource(R.string.setup_pc_or_shell_paste), style = SsType.Caption, color = SsColors.TextSecondary)
    Commands(cmds.shellPaste)
}

private fun openDevSettings(context: Context) {
    val intents = listOf(
        Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS),
        Intent(Settings.ACTION_SETTINGS),
    )
    for (i in intents) {
        try {
            context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return
        } catch (_: Exception) {
        }
    }
}
