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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import io.github.devjardins.sidespeed.R
import io.github.devjardins.sidespeed.apps.AppEntry
import io.github.devjardins.sidespeed.compile.CompileMode
import io.github.devjardins.sidespeed.compile.CompileOutcome
import io.github.devjardins.sidespeed.compile.PackageName
import io.github.devjardins.sidespeed.ui.about.rememberQrBitmap
import io.github.devjardins.sidespeed.ui.apps.AppIcon
import io.github.devjardins.sidespeed.apps.IconSource
import io.github.devjardins.sidespeed.ui.text.UiText
import io.github.devjardins.sidespeed.ui.text.resolve
import io.github.devjardins.sidespeed.ui.text.uiText
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

/** "Success" / "Failure", plus the reason when the outcome adds one. */
fun resultBanner(outcome: CompileOutcome): UiText = when (outcome) {
    CompileOutcome.Success -> UiText.Res(R.string.compile_result_success)
    CompileOutcome.Failure -> UiText.Res(R.string.compile_result_failure)
    CompileOutcome.ConnectionLost ->
        uiText(R.string.compile_result_failure_detail, UiText.Res(R.string.maint_job_connection_lost))
    is CompileOutcome.Error -> {
        val reason = outcome.detail?.takeIf { it.isNotBlank() }?.let(UiText::Raw)
            ?: UiText.Res(R.string.maint_job_error)
        uiText(R.string.compile_result_failure_detail, reason)
    }
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
                    installedVersion = app.versionName ?: stringResource(R.string.compile_update_version_unknown),
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
                    Text(stringResource(R.string.compile_mode_label), style = SsType.Caption, color = SsColors.TextSecondary)
                    CompileMode.entries.forEach { m ->
                        SsButton(
                            onClick = { onModeChange(m) },
                            selected = m == mode,
                            enabled = !launchBusy,
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                        ) {
                            Text(
                                if (m == mode) stringResource(R.string.compile_selected, m.arg) else m.arg,
                                style = SsType.Caption,
                            )
                        }
                    }
                }
            }

            when {
                state.running -> {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        SsSpinner(24.dp)
                        Text(
                            stringResource(R.string.compile_compiling_elapsed, formatElapsed(elapsed)),
                            style = SsType.Title,
                            color = SsColors.TextPrimary,
                        )
                    }
                    Text(stringResource(R.string.compile_running_note), style = SsType.Body, color = SsColors.TextSecondary)
                }
                result == null -> {
                    Text(stringResource(R.string.compile_note), style = SsType.Body, color = SsColors.TextBody)
                    SsButton(
                        stringResource(R.string.compile_start),
                        onClick = { if (pkg != null) vm.startMaybeTimed(pkg, mode, app.label) },
                        enabled = pkg != null && !launchBusy,
                        modifier = Modifier.focusRequester(startFocus),
                    )
                    if (pkg != null) KeepToggle(state.kept, pkg, vm, onKeepOptimized, enabled = !launchBusy)
                }
                result.success -> SsCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            uiText(R.string.compile_success_title_checked, successTitle(mode)).resolve(),
                            style = SsType.Title,
                            color = SsColors.Accent,
                        )
                        Text(
                            successDetail(state.durationSeconds, mode, state.badge).resolve(),
                            style = SsType.Body,
                            color = SsColors.TextBody,
                        )
                    }
                }
                else -> SsCard(Modifier.fillMaxWidth(), container = SsColors.DangerCardBg, content = SsColors.DangerCardText) {
                    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(resultBanner(result.outcome).resolve(), style = SsType.Title, color = SsColors.DangerCardText)
                        Text(stringResource(R.string.compile_failure_see_log), style = SsType.Body, color = SsColors.DangerCardText)
                    }
                }
            }

            if (result != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    SsButton(
                        stringResource(R.string.compile_back_to_apps),
                        onClick = onBack,
                        enabled = !launchBusy,
                        modifier = Modifier.focusRequester(backFocus),
                    )
                    SsButton(
                        stringResource(R.string.compile_again),
                        onClick = { if (pkg != null) vm.startMaybeTimed(pkg, mode, app.label) },
                        enabled = pkg != null && !launchBusy,
                        modifier = Modifier.focusRequester(startFocus),
                    )
                    if (state.offerKeepOptimized && pkg != null) {
                        SsButton(
                            stringResource(R.string.compile_keep_optimized),
                            onClick = { vm.keepOptimized(pkg, onKeepOptimized) },
                            enabled = !launchBusy,
                        )
                    }
                    SsButton(
                        stringResource(if (showLog) R.string.compile_hide_log else R.string.compile_show_log),
                        onClick = { showLog = !showLog },
                        enabled = !launchBusy,
                    )
                    if (result.outcome is CompileOutcome.ConnectionLost) {
                        SsButton(stringResource(R.string.compile_check_helper), onClick = onCheckHelper, enabled = !launchBusy)
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
            Text(stringResource(R.string.compile_update_available), style = SsType.Title, color = SsColors.TextPrimary)
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
                    stringResource(R.string.compile_update_elsewhere_running),
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
                    contentDescription = stringResource(R.string.compile_update_qr_cd, link),
                    modifier = Modifier.size(160.dp),
                )
                Text(link, style = SsType.Body, color = SsColors.TextBody)
                Text(
                    stringResource(R.string.compile_update_scan_or_type),
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
                stringResource(R.string.compile_update_summary, formatUpdateSize(variant.size), updateHost(variant.url)),
                style = SsType.Body,
                color = SsColors.TextBody,
            )
            SsButton(stringResource(R.string.compile_update_now), onClick = onUpdateNow)
        }
        is AppUpdateUiState.Downloading -> {
            SsRow(onClick = {}, modifier = Modifier.fillMaxWidth().focusRequester(progressFocus)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    SsSpinner(24.dp)
                    Text(
                        stringResource(R.string.compile_update_downloading, updateState.percent),
                        style = SsType.Title,
                        color = SsColors.TextPrimary,
                    )
                }
            }
        }
        is AppUpdateUiState.Checking -> {
            SsRow(onClick = {}, modifier = Modifier.fillMaxWidth().focusRequester(progressFocus)) {
                Text(stringResource(R.string.compile_update_checking), style = SsType.Title, color = SsColors.TextPrimary)
            }
        }
        is AppUpdateUiState.Installing -> {
            SsRow(onClick = {}, modifier = Modifier.fillMaxWidth().focusRequester(progressFocus)) {
                Text(stringResource(R.string.compile_update_installing), style = SsType.Title, color = SsColors.TextPrimary)
            }
        }
        is AppUpdateUiState.AwaitingConfirmation -> {
            SsRow(onClick = {}, modifier = Modifier.fillMaxWidth().focusRequester(progressFocus)) {
                Text(stringResource(R.string.compile_update_confirm), style = SsType.Body, color = SsColors.TextBody)
            }
        }
        is AppUpdateUiState.Done -> {
            SsRow(onClick = {}, modifier = Modifier.fillMaxWidth().focusRequester(doneFocus)) {
                Text(
                    stringResource(R.string.compile_update_done, updateState.versionName),
                    style = SsType.Title,
                    color = SsColors.Accent,
                )
            }
        }
        is AppUpdateUiState.Failed -> {
            Text(updateState.reason, style = SsType.Body, color = SsColors.TextBody)
            SsButton(
                stringResource(R.string.compile_update_try_again),
                onClick = onUpdateNow,
                modifier = Modifier.focusRequester(retryFocus),
            )
        }
    }
}

@Composable
private fun KeepToggle(kept: Boolean, pkg: PackageName, vm: CompileViewModel, onKept: () -> Unit, enabled: Boolean = true) {
    if (kept) {
        SsButton(stringResource(R.string.compile_kept_optimized), onClick = { vm.stopKeeping(pkg) }, enabled = enabled)
    } else {
        SsButton(stringResource(R.string.compile_keep_optimized), onClick = { vm.keepOptimized(pkg, onKept) }, enabled = enabled)
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
