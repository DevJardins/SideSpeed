package io.github.devjardins.sidespeed.ui.setup

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import io.github.devjardins.sidespeed.adb.Readiness
import io.github.devjardins.sidespeed.adb.pcStartCommands
import io.github.devjardins.sidespeed.helper.HelperLaunch

const val LAN_WARNING =
    "ADB debugging must stay on for SideSpeed to work. Anyone on your network could ask your TV for access; " +
        "SideSpeed only uses it to compile apps. Turning ADB off, or restarting the TV, stops SideSpeed's " +
        "helper until you start it again."

const val ALLOW_PROMPT_TEXT = "Check the TV for an 'Allow debugging' prompt and choose Always allow."

@Composable
fun SetupScreen(vm: SetupViewModel, onReady: () -> Unit) {
    val state by vm.state.collectAsState()
    LaunchedEffect(Unit) { vm.start() }
    LaunchedEffect(state) { if (state == SetupUiState.Ready) onReady() }

    Column(
        Modifier.fillMaxSize().padding(48.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("SideSpeed setup", style = MaterialTheme.typography.displaySmall)
        when (val s = state) {
            SetupUiState.Loading, SetupUiState.Checking, SetupUiState.Ready -> Text("Checking the helper...")
            SetupUiState.NeedsWarningAck -> {
                Text(LAN_WARNING)
                MainButton("I understand", onClick = vm::acknowledgeWarning)
            }
            is SetupUiState.Result -> ResultContent(vm, s)
        }
    }
}

@Composable
private fun MainButton(label: String, autoFocus: Boolean = true, onClick: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { if (autoFocus) runCatching { focus.requestFocus() } }
    Button(onClick = onClick, modifier = Modifier.focusRequester(focus)) { Text(label) }
}

@Composable
private fun ResultContent(vm: SetupViewModel, s: SetupUiState.Result) {
    val context = LocalContext.current
    when (val r = s.readiness) {
        Readiness.WaitingForAllowPrompt -> {
            Text(ALLOW_PROMPT_TEXT)
            MainButton("Check again", onClick = vm::checkAgain)
        }
        is Readiness.NeedsHelperStart, Readiness.HelperNeedsRestart -> {
            val stale = r == Readiness.HelperNeedsRestart
            if (r is Readiness.NeedsHelperStart) Text(r.reason)
            if (stale) Text("The helper is running but out of date (SideSpeed was updated). Restart it.")
            StartHelperContent(vm, s, stale, context)
            MainButton("Check again", autoFocus = vm.isFireOs, onClick = vm::checkAgain)
        }
        else -> Unit
    }
}

@Composable
private fun StartHelperContent(vm: SetupViewModel, s: SetupUiState.Result, stale: Boolean, context: Context) {
    val cmds = pcStartCommands(vm.wifiIp(), HelperLaunch.command(), killFirst = stale)
    if (vm.isFireOs) {
        Text("1. Turn on ADB debugging in Developer options.")
        Button(onClick = { openDevSettings(context) }) { Text("Open developer settings") }
        Text("2. From a PC on the same network, run:")
        cmds.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
        Text("Phone app coming later.")
    } else {
        Text("1. Turn on Wireless debugging in Developer options, then choose Pair device with pairing code.")
        Button(onClick = { openDevSettings(context) }) { Text("Open developer settings") }
        var code by remember { mutableStateOf("") }
        var port by remember { mutableStateOf("") }
        Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
            PairingCodeGrid("Pairing code", code, 6, { code = it }, focusFirst = true)
            PairingCodeGrid("Pairing port", port, 5, { port = it })
        }
        Button(onClick = { vm.pair(code, port) }, enabled = !s.pairing) { Text("Pair") }
        s.pairMessage?.let { Text(it) }
        Text("Or from a PC on the same network, run:")
        cmds.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
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
