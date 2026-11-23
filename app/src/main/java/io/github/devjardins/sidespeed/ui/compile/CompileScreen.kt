package io.github.devjardins.sidespeed.ui.compile

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import io.github.devjardins.sidespeed.ui.about.rememberQrBitmap
import io.github.devjardins.sidespeed.ui.apps.AppIcon
import io.github.devjardins.sidespeed.apps.IconSource
import io.github.devjardins.sidespeed.ui.theme.CardShape
import io.github.devjardins.sidespeed.ui.theme.SsButton
import io.github.devjardins.sidespeed.ui.theme.SsCard
import io.github.devjardins.sidespeed.ui.theme.SsRow
import io.github.devjardins.sidespeed.ui.theme.SsColors
import io.github.devjardins.sidespeed.ui.theme.SsDimens
import io.github.devjardins.sidespeed.ui.theme.SsScreen
import io.github.devjardins.sidespeed.ui.theme.SsSpinner
import io.github.devjardins.sidespeed.ui.theme.SsType
import io.github.devjardins.sidespeed.updates.ApkVariant
import io.github.devjardins.sidespeed.updates.AppUpdateUiState
import io.github.devjardins.sidespeed.updates.AvailableUpdate
import io.github.devjardins.sidespeed.updates.updateCardTitle
import io.github.devjardins.sidespeed.updates.updateSourceLine
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

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
    onModeChange: (CompileMode) -> Unit = {},
    availableUpdate: AvailableUpdate? = null,
    pickedVariant: ApkVariant? = null,
    appUpdateState: AppUpdateUiState = AppUpdateUiState.Idle,
    updatePkg: String? = null,
    onUpdateNow: () -> Unit = {},
) {
    val state by vm.state.collectAsState()
    val launch by vm.launch.collectAsState()
    val pkg = remember(app) { PackageName.parse(app.packageName) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val result = state.result
    val elapsed = ((now - state.startedAtMs) / 1000).coerceAtLeast(0)
    val startFocus = remember { FocusRequester() }
    val backFocus = remember { FocusRequester() }
    val logFocus = remember { FocusRequester() }
    // Collapsed on success, expanded on failure; the user can toggle it either way.
    var showLog by remember(result) { mutableStateOf(result != null && !result.success) }

    LaunchedEffect(app.packageName) {
        vm.reset()
        pkg?.let { vm.loadKept(it) }
        vm.loadLaunch(app.packageName)
        runCatching { startFocus.requestFocus() }
    }
    LaunchedEffect(state.running) {
        if (state.running) {
            runCatching { logFocus.requestFocus() }
            while (true) {
                now = System.currentTimeMillis()
                delay(500)
            }
        }
    }
    // Not while a launch sequence is busy: the busy indicator owns D-pad focus until it ends.
    LaunchedEffect(result) { if (result != null && launch.busy == null) runCatching { backFocus.requestFocus() } }

    SsScreen {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                AppIcon(app.packageName, icon, SsDimens.Icon + 12.dp)
                Column(Modifier.weight(1f)) {
                    Text(app.label, style = SsType.Headline, color = SsColors.TextPrimary)
                    Text("${app.packageName} · ${mode.arg}", style = SsType.Caption, color = SsColors.TextSecondary)
                }
            }

            // While a launch sequence is busy every action below is disabled and
            // Back is blocked (MainActivity): the runner owns the screen until it finishes.
            val launchBusy = launch.busy != null
            availableUpdate?.let {
                UpdateAvailableCard(
                    it,
                    installedVersion = app.versionName ?: "unknown",
                    pickedVariant = pickedVariant,
                    updateState = appUpdateState,
                    updatePkg = updatePkg,
                    onUpdateNow = onUpdateNow,
                )
            }
            if (launch.launchable || launch.stored != null) {
                LaunchTimeSection(
                    launch = launch,
                    onCheckedChange = vm::setLaunchChecked,
                    onMeasure = { if (pkg != null) vm.measure(pkg, app.label) },
                )
            }

            if (!state.running) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("Mode", style = SsType.Caption, color = SsColors.TextSecondary)
                    CompileMode.entries.forEach { m ->
                        SsButton(
                            onClick = { onModeChange(m) },
                            selected = m == mode,
                            enabled = !launchBusy,
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                        ) { Text(if (m == mode) "✓ ${m.arg}" else m.arg, style = SsType.Caption) }
                    }
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
                        onClick = { if (pkg != null) vm.startMaybeTimed(pkg, mode, app.label) },
                        enabled = pkg != null && !launchBusy,
                        modifier = Modifier.focusRequester(startFocus),
                    )
                    if (pkg != null) KeepToggle(state.kept, pkg, vm, onKeepOptimized, enabled = !launchBusy)
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
                    SsButton(
                        "Back to apps",
                        onClick = onBack,
                        enabled = !launchBusy,
                        modifier = Modifier.focusRequester(backFocus),
                    )
                    SsButton(
                        "Compile again",
                        onClick = { if (pkg != null) vm.startMaybeTimed(pkg, mode, app.label) },
                        enabled = pkg != null && !launchBusy,
                        modifier = Modifier.focusRequester(startFocus),
                    )
                    if (state.offerKeepOptimized && pkg != null) {
                        SsButton(
                            "Keep this app optimized",
                            onClick = { vm.keepOptimized(pkg, onKeepOptimized) },
                            enabled = !launchBusy,
                        )
                    }
                    SsButton(
                        if (showLog) "Hide log" else "Show log",
                        onClick = { showLog = !showLog },
                        enabled = !launchBusy,
                    )
                    if (result.message == "Connection lost") {
                        SsButton("Check helper", onClick = onCheckHelper, enabled = !launchBusy)
                    }
                }
            }

            if (state.running || (result != null && showLog)) {
                LogView(state.log, Modifier.weight(1f).focusRequester(logFocus))
            }
        }
    }
}

/** "12 MB", "12.5 MB": the update download size in whole or one-decimal megabytes. */
fun formatUpdateSize(size: Long): String {
    val tenths = ((size / 1_048_576.0) * 10).roundToInt()
    return if (tenths % 10 == 0) "${tenths / 10} MB" else "${tenths / 10}.${tenths % 10} MB"
}

/** The host of an update URL ("f-droid.org"), or the URL itself when it has none. */
fun updateHost(url: String): String =
    runCatching { java.net.URI(url).host?.takeIf { it.isNotBlank() } }.getOrNull()
        ?: url.substringAfter("https://").substringBefore('/')

/**
 * Update notice above the launch-time card: what is out, what is installed,
 * where to get it (phone QR plus a link to type on this TV). When a variant
 * fits this TV, one Update now button downloads, checks and installs it;
 * otherwise the card stays QR-only and nothing is downloaded.
 */
@Composable
private fun UpdateAvailableCard(
    update: AvailableUpdate,
    installedVersion: String,
    pickedVariant: ApkVariant?,
    updateState: AppUpdateUiState,
    updatePkg: String?,
    onUpdateNow: () -> Unit,
) {
    // Another app's update is running: say so in plain text instead of offering
    // Update now, and keep this card's QR below as the manual way.
    val elsewhereRunning = updatePkg != null && updatePkg != update.packageName &&
        (updateState is AppUpdateUiState.Downloading ||
            updateState is AppUpdateUiState.Checking ||
            updateState is AppUpdateUiState.Installing)
    SsCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Update available", style = SsType.Title, color = SsColors.TextPrimary)
            Text(
                updateCardTitle(update.name, update.versionName, installedVersion),
                style = SsType.Body,
                color = SsColors.TextBody,
            )
            updateSourceLine(update.source)?.let {
                Text(it, style = SsType.Body, color = SsColors.TextBody)
            }
            if (elsewhereRunning) {
                Text(
                    "Another app is updating. Wait for it to finish.",
                    style = SsType.Body,
                    color = SsColors.TextBody,
                )
            } else if (pickedVariant != null) {
                UpdateNowSection(pickedVariant, updateState, onUpdateNow)
            }
            val link = update.link
            if (!link.isNullOrBlank()) {
                Image(
                    rememberQrBitmap(link),
                    contentDescription = "QR code for $link",
                    modifier = Modifier.size(160.dp),
                )
                Text(link, style = SsType.Body, color = SsColors.TextBody)
                Text(
                    "Scan with your phone, or type the link into a browser on this TV.",
                    style = SsType.Caption,
                    color = SsColors.TextSecondary,
                )
            }
        }
    }
}

/**
 * The one-press update: a caveat plus Update now above the QR, then progress
 * while it runs. The button carries no focus requester, so opening the screen
 * still focuses Start compile first; it stays reachable by D-pad like the rest.
 * Pressing it removes the focused button, so the progress line is focusable and
 * takes focus instead; Try again takes focus on failure, the done line on success.
 */
@Composable
private fun UpdateNowSection(
    variant: ApkVariant,
    updateState: AppUpdateUiState,
    onUpdateNow: () -> Unit,
) {
    val progressFocus = remember { FocusRequester() }
    val retryFocus = remember { FocusRequester() }
    val doneFocus = remember { FocusRequester() }
    LaunchedEffect(updateState) {
        when (updateState) {
            is AppUpdateUiState.Idle -> Unit
            is AppUpdateUiState.Failed -> runCatching { retryFocus.requestFocus() }
            is AppUpdateUiState.Done -> runCatching { doneFocus.requestFocus() }
            else -> runCatching { progressFocus.requestFocus() }
        }
    }
    when (updateState) {
        is AppUpdateUiState.Idle -> {
            Text(
                "Downloads ${formatUpdateSize(variant.size)} from ${updateHost(variant.url)}. " +
                    "SideSpeed checks it is a real update of the app you have, then installs it. " +
                    "Your data in the app stays.",
                style = SsType.Body,
                color = SsColors.TextBody,
            )
            SsButton("Update now", onClick = onUpdateNow)
        }
        is AppUpdateUiState.Downloading -> {
            SsRow(onClick = {}, modifier = Modifier.fillMaxWidth().focusRequester(progressFocus)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    SsSpinner(24.dp)
                    Text("Downloading… ${updateState.percent}%", style = SsType.Title, color = SsColors.TextPrimary)
                }
            }
        }
        is AppUpdateUiState.Checking -> {
            SsRow(onClick = {}, modifier = Modifier.fillMaxWidth().focusRequester(progressFocus)) {
                Text("Checking the download…", style = SsType.Title, color = SsColors.TextPrimary)
            }
        }
        is AppUpdateUiState.Installing -> {
            SsRow(onClick = {}, modifier = Modifier.fillMaxWidth().focusRequester(progressFocus)) {
                Text("Installing…", style = SsType.Title, color = SsColors.TextPrimary)
            }
        }
        is AppUpdateUiState.AwaitingConfirmation -> {
            SsRow(onClick = {}, modifier = Modifier.fillMaxWidth().focusRequester(progressFocus)) {
                Text("Your TV will ask you to confirm the install.", style = SsType.Body, color = SsColors.TextBody)
            }
        }
        is AppUpdateUiState.Done -> {
            SsRow(onClick = {}, modifier = Modifier.fillMaxWidth().focusRequester(doneFocus)) {
                Text("Updated to ${updateState.versionName}.", style = SsType.Title, color = SsColors.Accent)
            }
        }
        is AppUpdateUiState.Failed -> {
            Text(updateState.reason, style = SsType.Body, color = SsColors.TextBody)
            SsButton("Try again", onClick = onUpdateNow, modifier = Modifier.focusRequester(retryFocus))
        }
    }
}

@Composable
private fun KeepToggle(kept: Boolean, pkg: PackageName, vm: CompileViewModel, onKept: () -> Unit, enabled: Boolean = true) {
    if (kept) {
        SsButton("✓ Kept optimized (tap to stop)", onClick = { vm.stopKeeping(pkg) }, enabled = enabled)
    } else {
        SsButton("Keep this app optimized", onClick = { vm.keepOptimized(pkg, onKept) }, enabled = enabled)
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
