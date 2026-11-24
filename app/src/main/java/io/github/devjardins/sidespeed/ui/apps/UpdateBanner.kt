package io.github.devjardins.sidespeed.ui.apps

import android.content.pm.ApplicationInfo
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import io.github.devjardins.sidespeed.R
import io.github.devjardins.sidespeed.tools.ToolsStore
import io.github.devjardins.sidespeed.tools.UpdateCheck
import io.github.devjardins.sidespeed.tools.displayVersion
import io.github.devjardins.sidespeed.tools.shouldShowBanner
import io.github.devjardins.sidespeed.ui.text.UiText
import io.github.devjardins.sidespeed.ui.text.resolve
import io.github.devjardins.sidespeed.ui.text.uiText
import io.github.devjardins.sidespeed.ui.theme.SsButton
import io.github.devjardins.sidespeed.ui.theme.SsCard
import io.github.devjardins.sidespeed.ui.theme.SsColors
import io.github.devjardins.sidespeed.ui.theme.SsType
import io.github.devjardins.sidespeed.update.SelfUpdater
import io.github.devjardins.sidespeed.update.UpdateUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * Slot for the [AppsScreen] banner: runs the update check once per appearance and shows the
 * banner only when a newer release is known and the user hasn't dismissed that version.
 */
@Composable
fun UpdateBannerSlot(
    check: UpdateCheck,
    store: ToolsStore,
    currentVersion: String,
    selfUpdater: SelfUpdater,
    busy: Flow<Boolean>,
) {
    LaunchedEffect(Unit) {
        launch(Dispatchers.IO) { check.checkIfDue() }
    }
    val latest by store.updateLatestVersion.collectAsState(initial = null)
    val dismissed by store.updateDismissedVersion.collectAsState(initial = null)
    val scope = rememberCoroutineScope()
    // Debug builds show the banner on demand so the notice can be verified on a TV/emulator
    // without waiting for a real release. Release builds only show a genuinely newer version.
    val context = LocalContext.current
    val effectiveLatest = remember(latest) {
        val debuggable = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        if (debuggable) "9.9.9" else latest
    }
    if (shouldShowBanner(effectiveLatest, dismissed, currentVersion)) {
        val tag = effectiveLatest!!
        val updateState by selfUpdater.state.collectAsState()
        val isBusy by busy.collectAsState(initial = false)
        UpdateBanner(
            latest = displayVersion(tag),
            state = updateState,
            updateEnabled = updateState == UpdateUiState.Available && !isBusy,
            showBusyCaption = updateState == UpdateUiState.Busy ||
                (updateState == UpdateUiState.Available && isBusy),
            onUpdate = { selfUpdater.start(tag) },
            onDismiss = {
                scope.launch { store.setUpdateDismissedVersion(tag) }
            },
            onCancel = { selfUpdater.cancel() },
            onRetry = { selfUpdater.start(tag) },
        )
    }
}

/** "SideSpeed X is out" banner with in-app update. */
@Composable
fun UpdateBanner(
    latest: String,
    state: UpdateUiState,
    updateEnabled: Boolean,
    showBusyCaption: Boolean,
    onUpdate: () -> Unit,
    onDismiss: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
) {
    // Dismiss keeps the initial focus, as before: starting an update is never one press away.
    val dismissFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        try {
            dismissFocus.requestFocus()
        } catch (_: IllegalStateException) {
            // Not yet attached; the next focus pass lands on Dismiss first anyway.
        }
    }
    SsCard(Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    when (state) {
                        is UpdateUiState.Downloading -> stringResource(R.string.update_banner_downloading, state.percent)
                        is UpdateUiState.Installing -> stringResource(R.string.update_banner_installing)
                        else -> uiText(R.string.update_banner_out, UiText.Ltr(latest)).resolve()
                    },
                    style = SsType.Body,
                    color = SsColors.TextPrimary,
                )
                if (state is UpdateUiState.Failed) {
                    Text(
                        state.reason,
                        style = SsType.Caption,
                        color = SsColors.TextSecondary,
                    )
                }
                if (showBusyCaption) {
                    Text(
                        stringResource(R.string.update_banner_busy),
                        style = SsType.Caption,
                        color = SsColors.TextSecondary,
                    )
                }
                Text(
                    stringResource(R.string.update_banner_manual),
                    style = SsType.Caption,
                    color = SsColors.TextSecondary,
                )
            }
            when (state) {
                is UpdateUiState.Available -> {
                    SsButton(stringResource(R.string.update_banner_update_now), onClick = onUpdate, enabled = updateEnabled)
                    SsButton(stringResource(R.string.update_banner_dismiss), onClick = onDismiss, modifier = Modifier.focusRequester(dismissFocus))
                }
                is UpdateUiState.Busy -> {
                    SsButton(stringResource(R.string.update_banner_update_now), onClick = onUpdate, enabled = false)
                    SsButton(stringResource(R.string.update_banner_dismiss), onClick = onDismiss, modifier = Modifier.focusRequester(dismissFocus))
                }
                is UpdateUiState.Downloading -> {
                    SsButton(stringResource(R.string.update_banner_cancel), onClick = onCancel)
                }
                is UpdateUiState.Installing -> {
                    // Nothing to press: the app closes and reopens on its own.
                }
                is UpdateUiState.Failed -> {
                    SsButton(stringResource(R.string.update_banner_retry), onClick = onRetry)
                    SsButton(stringResource(R.string.update_banner_dismiss), onClick = onDismiss)
                }
            }
        }
    }
}
