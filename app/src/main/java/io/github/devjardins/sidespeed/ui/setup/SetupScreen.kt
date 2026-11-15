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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.tv.material3.Text
import io.github.devjardins.sidespeed.adb.DeviceKind
import io.github.devjardins.sidespeed.adb.PcStartCommands
import io.github.devjardins.sidespeed.adb.Readiness
import io.github.devjardins.sidespeed.adb.pcStartCommands
import io.github.devjardins.sidespeed.helper.HelperLaunch
import io.github.devjardins.sidespeed.ui.theme.SsButton
import io.github.devjardins.sidespeed.ui.theme.SsCard
import io.github.devjardins.sidespeed.ui.theme.SsColors
import io.github.devjardins.sidespeed.ui.theme.SsScreen
import io.github.devjardins.sidespeed.ui.theme.SsSpinner
import io.github.devjardins.sidespeed.ui.theme.SsType

const val LAN_WARNING =
    "ADB debugging must stay on for SideSpeed to work. Anyone on your network could ask your TV for access; " +
        "SideSpeed only uses it to compile apps. Turning ADB off, or restarting the TV, stops SideSpeed's " +
        "helper until you start it again."

const val SETUP_HEADLINE = "Allow SideSpeed on this TV"

const val REFUSED_HINT = "Your TV doesn't accept direct connections. Pair with a code instead."

const val WINDOWS_NOTE = "On Windows use PowerShell or Terminal, not Command Prompt."

const val WAITING_TEXT = "Waiting for you…"

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
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when (val s = state) {
                SetupUiState.Loading, SetupUiState.Ready -> {
                    Text(SETUP_HEADLINE, style = SsType.Headline, color = SsColors.TextPrimary)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        SsSpinner()
                        Text("Checking…", style = SsType.Body, color = SsColors.TextBody)
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
    Text("Before you start", style = SsType.Headline, color = SsColors.TextPrimary)
    SsCard(Modifier.fillMaxWidth()) {
        Text(LAN_WARNING, style = SsType.Body, color = SsColors.TextBody, modifier = Modifier.padding(24.dp))
    }
    SsButton("I understand", onClick = vm::acknowledgeWarning, modifier = Modifier.focusRequester(focus))
}

private enum class StepState { TODO, WAITING, WORKING, DONE }

@Composable
private fun StepCard(number: Int, title: String, state: StepState, statusText: String? = null, extra: @Composable () -> Unit = {}) {
    SsCard(Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
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

    Text(SETUP_HEADLINE, style = SsType.Headline, color = SsColors.TextPrimary)
    StepCard(
        1, "Turn on Wireless debugging",
        if (s.detected) StepState.DONE else StepState.TODO,
        if (s.detected) "Detected" else null,
    ) { Text("Settings > System > Developer options", style = SsType.Caption, color = SsColors.TextSecondary) }
    StepCard(
        2, "When your TV asks, tick Always allow, then Allow",
        when {
            s.launching -> StepState.DONE
            s.waiting -> StepState.WAITING
            else -> StepState.TODO
        },
        if (!s.launching && s.waiting) WAITING_TEXT else null,
    )
    StepCard(3, "SideSpeed starts its helper", if (s.launching) StepState.WORKING else StepState.TODO)

    if (s.refusedHint && !s.showPairing) {
        Text(REFUSED_HINT, style = SsType.Body, color = SsColors.WarnPillText)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        SsButton("Open developer settings", onClick = { openDevSettings(context) }, modifier = Modifier.focusRequester(settingsFocus))
        if (s.showPairing) {
            SsButton("Hide pairing", onClick = vm::hidePairing, modifier = Modifier.focusRequester(pairFocus))
        } else {
            SsButton("Pair with a code instead", onClick = vm::showPairing, modifier = Modifier.focusRequester(pairFocus))
        }
    }
    if (s.showPairing) PairingContent(vm, s)
    StatusCaption(s.readiness)
}

@Composable
private fun PairingContent(vm: SetupViewModel, s: SetupUiState.Connecting) {
    SsCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Pair with a code", style = SsType.Title, color = SsColors.TextPrimary)
            Text(
                "In Wireless debugging, choose Pair device with pairing code. Enter the code and the port " +
                    "shown there (the number after the colon).",
                style = SsType.Body, color = SsColors.TextBody,
            )
            var code by remember { mutableStateOf("") }
            var port by remember { mutableStateOf("") }
            Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                PairingCodeGrid("Pairing code", code, 6, { code = it }, focusFirst = true)
                PairingCodeGrid("Pairing port", port, 5, { port = it })
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                SsButton("Pair", onClick = { vm.pair(code, port) }, enabled = !s.pairing)
                if (s.pairing) SsSpinner()
                s.pairMessage?.let { Text(it, style = SsType.Body, color = SsColors.TextBody) }
            }
            Spacer(Modifier.size(4.dp))
            Text(
                "Or start the helper from a PC on the same network, using the addresses shown under Wireless debugging:",
                style = SsType.Caption, color = SsColors.TextSecondary,
            )
            PcCommands(pcStartCommands(DeviceKind.GOOGLE_TV, vm.wifiIp(), HelperLaunch.command(), killFirst = false))
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

    Text(SETUP_HEADLINE, style = SsType.Headline, color = SsColors.TextPrimary)
    StepCard(
        1, "Turn on ADB debugging",
        if (s.detected) StepState.DONE else StepState.TODO,
        if (s.detected) "Detected" else null,
    ) { Text("Settings > My Fire TV > Developer options", style = SsType.Caption, color = SsColors.TextSecondary) }
    StepCard(2, "Start the helper from a PC", StepState.WAITING, WAITING_TEXT) {
        if (stale) {
            Text(
                "The helper is running but out of date (SideSpeed was updated). Restart it:",
                style = SsType.Body, color = SsColors.WarnPillText,
            )
        } else {
            Text("From a PC on the same network, run:", style = SsType.Body, color = SsColors.TextBody)
        }
        PcCommands(cmds)
        Text("Phone app coming later.", style = SsType.Caption, color = SsColors.TextSecondary)
    }
    StepCard(3, "Helper starts", StepState.TODO, "Checks automatically")
    SsButton("Open developer settings", onClick = { openDevSettings(context) }, modifier = Modifier.focusRequester(settingsFocus))
}

/** A quiet line with the last check's result, for troubleshooting. */
@Composable
private fun StatusCaption(r: Readiness?) {
    val text = when (r) {
        null, Readiness.HelperReady -> return
        Readiness.WaitingForAllowPrompt -> "Last check: waiting for the Allow debugging prompt"
        Readiness.HelperNeedsRestart -> "Last check: the helper is out of date"
        is Readiness.NeedsHelperStart -> "Last check: ${r.reason}"
    }
    Text(text, style = SsType.Caption, color = SsColors.TextSecondary)
}

@Composable
private fun PcCommands(cmds: PcStartCommands) {
    val mono = SsType.Body.copy(fontFamily = FontFamily.Monospace)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        cmds.connect.forEach { Text(it, style = mono, color = SsColors.TextBody) }
        if (cmds.connect.any { it.startsWith("adb pair") }) {
            Text("(enter the pairing code when asked)", style = SsType.Caption, color = SsColors.TextSecondary)
        }
        cmds.run.forEach { Text(it, style = mono, color = SsColors.TextBody) }
        Text(WINDOWS_NOTE, style = SsType.Caption, color = SsColors.TextSecondary)
        Text("Or type adb shell, then paste:", style = SsType.Caption, color = SsColors.TextSecondary)
        cmds.shellPaste.forEach { Text(it, style = mono, color = SsColors.TextBody) }
        Spacer(Modifier.width(1.dp))
    }
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
