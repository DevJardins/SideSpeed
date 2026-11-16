package io.github.devjardins.sidespeed.ui.compile

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import io.github.devjardins.sidespeed.apps.AppEntry
import io.github.devjardins.sidespeed.compile.CompileMode
import io.github.devjardins.sidespeed.compile.PackageName
import io.github.devjardins.sidespeed.ui.apps.AppIcon
import io.github.devjardins.sidespeed.apps.IconSource
import io.github.devjardins.sidespeed.ui.theme.CardShape
import io.github.devjardins.sidespeed.ui.theme.SsButton
import io.github.devjardins.sidespeed.ui.theme.SsCard
import io.github.devjardins.sidespeed.ui.theme.SsColors
import io.github.devjardins.sidespeed.ui.theme.SsDimens
import io.github.devjardins.sidespeed.ui.theme.SsScreen
import io.github.devjardins.sidespeed.ui.theme.SsSpinner
import io.github.devjardins.sidespeed.ui.theme.SsType
import kotlinx.coroutines.delay

const val RUNNING_NOTE = "A compile is running — please wait until it finishes."

const val COMPILE_NOTE = "This can take several minutes for large apps (about 3 min on a Fire TV Cube)"

/** "Success" / "Failure", plus the message only when it adds something (avoids "Success: Success"). */
fun resultBanner(success: Boolean, message: String): String {
    val label = if (success) "Success" else "Failure"
    return if (message.isBlank() || message == label) label else "$label: $message"
}

fun formatElapsed(seconds: Long): String = "%02d:%02d".format(seconds / 60, seconds % 60)

@Composable
fun CompileScreen(
    vm: CompileViewModel,
    app: AppEntry,
    mode: CompileMode,
    icon: IconSource,
    onBack: () -> Unit,
    onCheckHelper: () -> Unit,
    onKeepOptimized: () -> Unit = {},
) {
    val state by vm.state.collectAsState()
    val pkg = remember(app) { PackageName.parse(app.packageName) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val result = state.result
    val elapsed = ((now - state.startedAtMs) / 1000).coerceAtLeast(0)
    val startFocus = remember { FocusRequester() }
    val backFocus = remember { FocusRequester() }
    val logFocus = remember { FocusRequester() }
    // Collapsed on success, expanded on failure; the user can toggle it either way.
    var showLog by remember(result) { mutableStateOf(result != null && !result.success) }

    LaunchedEffect(app.packageName) { vm.reset(); pkg?.let { vm.loadKept(it) }; runCatching { startFocus.requestFocus() } }
    LaunchedEffect(state.running) {
        if (state.running) {
            runCatching { logFocus.requestFocus() }
            while (true) {
                now = System.currentTimeMillis()
                delay(500)
            }
        }
    }
    LaunchedEffect(result) { if (result != null) runCatching { backFocus.requestFocus() } }

    SsScreen {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                AppIcon(app.packageName, icon, SsDimens.Icon + 12.dp)
                Column(Modifier.weight(1f)) {
                    Text(app.label, style = SsType.Headline, color = SsColors.TextPrimary)
                    Text("${app.packageName} · ${mode.arg}", style = SsType.Caption, color = SsColors.TextSecondary)
                }
            }

            when {
                state.running -> {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        SsSpinner(24.dp)
                        Text("Compiling… ${formatElapsed(elapsed)}", style = SsType.Title, color = SsColors.TextPrimary)
                    }
                    Text(RUNNING_NOTE, style = SsType.Body, color = SsColors.TextSecondary)
                }
                result == null -> {
                    Text(COMPILE_NOTE, style = SsType.Body, color = SsColors.TextBody)
                    SsButton(
                        "Start compile",
                        onClick = { if (pkg != null) vm.start(pkg, mode) },
                        enabled = pkg != null,
                        modifier = Modifier.focusRequester(startFocus),
                    )
                    if (pkg != null) KeepToggle(state.kept, pkg, vm, onKeepOptimized)
                }
                result.success -> SsCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("✓ " + successTitle(mode), style = SsType.Title, color = SsColors.Accent)
                        Text(
                            successDetail(state.durationSeconds, mode, state.badge),
                            style = SsType.Body,
                            color = SsColors.TextBody,
                        )
                    }
                }
                else -> SsCard(Modifier.fillMaxWidth(), container = SsColors.DangerCardBg, content = SsColors.DangerCardText) {
                    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(resultBanner(false, result.message), style = SsType.Title, color = SsColors.DangerCardText)
                        Text("See the log below for details.", style = SsType.Body, color = SsColors.DangerCardText)
                    }
                }
            }

            if (result != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    SsButton("Back to apps", onClick = onBack, modifier = Modifier.focusRequester(backFocus))
                    SsButton(
                        "Compile again",
                        onClick = { if (pkg != null) vm.start(pkg, mode) },
                        enabled = pkg != null,
                        modifier = Modifier.focusRequester(startFocus),
                    )
                    if (state.offerKeepOptimized && pkg != null) {
                        SsButton("Keep this app optimized", onClick = { vm.keepOptimized(pkg, onKeepOptimized) })
                    }
                    SsButton(if (showLog) "Hide log" else "Show log", onClick = { showLog = !showLog })
                    if (result.message == "Connection lost") {
                        SsButton("Check helper", onClick = onCheckHelper)
                    }
                }
            }

            if (state.running || (result != null && showLog)) {
                LogView(state.log, Modifier.weight(1f).focusRequester(logFocus))
            }
        }
    }
}

@Composable
private fun KeepToggle(kept: Boolean, pkg: PackageName, vm: CompileViewModel, onKept: () -> Unit) {
    if (kept) {
        SsButton("✓ Kept optimized (tap to stop)", onClick = { vm.stopKeeping(pkg) })
    } else {
        SsButton("Keep this app optimized", onClick = { vm.keepOptimized(pkg); onKept() })
    }
}

@Composable
private fun LogView(log: List<String>, modifier: Modifier) {
    val listState = rememberLazyListState()
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(log.size) { if (log.isNotEmpty()) listState.scrollToItem(log.size - 1) }
    LazyColumn(
        state = listState,
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged { focused = it.hasFocus }
            .background(if (focused) SsColors.Raised else SsColors.Surface, CardShape)
            .border(BorderStroke(SsDimens.FocusRing, if (focused) SsColors.Accent else SsColors.Surface), CardShape)
            .focusable()
            .padding(16.dp),
    ) {
        itemsIndexed(log) { _, line ->
            Text(line, style = SsType.Caption.copy(fontFamily = FontFamily.Monospace), color = SsColors.TextBody)
        }
    }
}
