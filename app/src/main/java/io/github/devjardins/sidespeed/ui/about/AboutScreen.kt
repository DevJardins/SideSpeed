package io.github.devjardins.sidespeed.ui.about

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import io.github.devjardins.sidespeed.R
import io.github.devjardins.sidespeed.tools.ManualCheckResult
import io.github.devjardins.sidespeed.tools.displayVersion
import io.github.devjardins.sidespeed.ui.text.UiText
import io.github.devjardins.sidespeed.ui.text.resolve
import io.github.devjardins.sidespeed.ui.text.uiText
import io.github.devjardins.sidespeed.ui.theme.SsButton
import io.github.devjardins.sidespeed.ui.theme.SsCard
import io.github.devjardins.sidespeed.ui.theme.SsColors
import io.github.devjardins.sidespeed.ui.theme.SsDimens
import io.github.devjardins.sidespeed.ui.theme.SsScreen
import io.github.devjardins.sidespeed.ui.theme.SsType
import io.github.devjardins.sidespeed.ui.theme.bleed
import io.github.devjardins.sidespeed.update.SelfUpdater
import io.github.devjardins.sidespeed.update.UpdateUiState
import kotlinx.coroutines.flow.Flow

/** The About screen: version, a manual update check, credits, and the Ko-fi QR. */
@Composable
fun AboutScreen(
    versionName: String,
    viewModel: AboutViewModel,
    selfUpdater: SelfUpdater,
    busy: Flow<Boolean>,
    onBack: () -> Unit,
    onOpenLanguage: () -> Unit,
    languageRowFocus: FocusRequester,
    focusLanguageRow: Boolean,
) {
    val ui by viewModel.state.collectAsState()
    val promptTag = ui.promptTag
    // Back closes the prompt first; only with no prompt does it leave the screen.
    BackHandler(enabled = promptTag == null, onBack = onBack)
    BackHandler(enabled = promptTag != null) { viewModel.dismissPrompt() }
    // Give the D-pad a starting point: the Back button takes focus when the screen opens,
    // unless returning from the Language screen, which focuses the Language row instead.
    val backFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { if (!focusLanguageRow) runCatching { backFocus.requestFocus() } }
    LaunchedEffect(focusLanguageRow) { if (focusLanguageRow) runCatching { languageRowFocus.requestFocus() } }
    // Focus state via the interaction source (the same channel tv-material buttons
    // use), driving the QR frame's ring below.
    val qrInteraction = remember { MutableInteractionSource() }
    val qrFocused by qrInteraction.collectIsFocusedAsState()
    // Encoded once, not per recomposition.
    val qr = rememberQrBitmap(KOFI_URL)
    SsScreen {
        // Same scroll treatment as ToolsScreen: bleed the column so the focus ring
        // is never clipped at the edges.
        val scrollInset = 8.dp
        Column(
            Modifier
                .fillMaxSize()
                .bleed(horizontal = scrollInset, vertical = scrollInset)
                .verticalScroll(rememberScrollState())
                .padding(scrollInset),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                SsButton(stringResource(R.string.common_back), onClick = onBack, modifier = Modifier.focusRequester(backFocus))
                Spacer(Modifier.width(16.dp))
                Text(stringResource(R.string.about_title), style = SsType.Headline, color = SsColors.TextPrimary)
            }
            SsCard(Modifier.fillMaxWidth()) {
                Column(
                    Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(stringResource(R.string.app_name), style = SsType.Title, color = SsColors.TextPrimary)
                    Text(
                        uiText(R.string.about_version, UiText.Ltr(versionName)).resolve(),
                        style = SsType.Body,
                        color = SsColors.TextBody,
                    )
                    SsButton(
                        stringResource(R.string.about_check_for_updates),
                        onClick = { viewModel.checkNow() },
                        enabled = !ui.checking,
                    )
                    SsButton(
                        stringResource(R.string.about_language),
                        onClick = onOpenLanguage,
                        modifier = Modifier.focusRequester(languageRowFocus),
                    )
                    // A newer release opens the prompt below instead of a line here;
                    // UpToDate/Failed keep their inline text.
                    val line = when {
                        ui.checking -> stringResource(R.string.about_checking)
                        ui.result is ManualCheckResult.UpToDate -> stringResource(R.string.about_up_to_date)
                        ui.result is ManualCheckResult.Failed -> stringResource(R.string.about_check_failed)
                        else -> null
                    }
                    line?.let {
                        Text(it, style = SsType.Body, color = SsColors.TextBody)
                    }
                }
            }
            SsCard(Modifier.fillMaxWidth()) {
                Column(
                    Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(stringResource(R.string.about_made_by), style = SsType.Body, color = SsColors.TextBody)
                    Text(UiText.Ltr("devjardins.com").resolve(), style = SsType.Body, color = SsColors.TextBody)
                    Text(
                        stringResource(R.string.about_support_kofi),
                        style = SsType.Title,
                        color = SsColors.TextPrimary,
                    )
                    Text(UiText.Ltr("ko-fi.com/devjardins").resolve(), style = SsType.Body, color = SsColors.TextBody)
                    // Focusable with no action: this lets the D-pad scroll the QR
                    // into view, like any other focused content on this screen.
                    // The Box sizes itself around the fixed image, so the focus
                    // ring draws outside the QR instead of over its modules.
                    Box(
                        Modifier
                            .focusable(interactionSource = qrInteraction)
                            .border(
                                BorderStroke(
                                    SsDimens.FocusRing,
                                    if (qrFocused) SsColors.Accent else Color.Transparent,
                                ),
                                RoundedCornerShape(6.dp),
                            )
                            .padding(SsDimens.FocusRing),
                    ) {
                        Image(
                            qr,
                            contentDescription = uiText(R.string.about_kofi_qr_cd, UiText.Ltr("ko-fi.com/devjardins")).resolve(),
                            modifier = Modifier.size(160.dp),
                        )
                    }
                    Text(
                        stringResource(R.string.about_scan_phone),
                        style = SsType.Caption,
                        color = SsColors.TextSecondary,
                    )
                }
            }
        }
        if (promptTag != null) {
            UpdatePromptDialog(
                tag = promptTag,
                selfUpdater = selfUpdater,
                busy = busy,
                onUpdateNow = { viewModel.onUpdateNow() },
                onLater = { viewModel.dismissPrompt() },
            )
        }
    }
}

/**
 * "SideSpeed X is available" prompt: Update now (focused first) runs the shared
 * [SelfUpdater] flow, so Downloading/Installing/Failed and the busy gate match the
 * Apps banner exactly. Later/Back only closes the prompt.
 */
@Composable
private fun UpdatePromptDialog(
    tag: String,
    selfUpdater: SelfUpdater,
    busy: Flow<Boolean>,
    onUpdateNow: () -> Unit,
    onLater: () -> Unit,
) {
    val updateState by selfUpdater.state.collectAsState()
    val isBusy by busy.collectAsState(initial = false)
    val primaryFocus = remember { FocusRequester() }
    // Land on the primary action whenever the prompt (or its action) appears.
    LaunchedEffect(tag, updateState) {
        runCatching { primaryFocus.requestFocus() }
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.6f)),
        contentAlignment = Alignment.Center,
    ) {
        SsCard(Modifier.fillMaxWidth(0.6f)) {
            Column(
                Modifier.padding(horizontal = 24.dp, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    when (updateState) {
                        is UpdateUiState.Downloading -> stringResource(
                            R.string.update_banner_downloading,
                            (updateState as UpdateUiState.Downloading).percent,
                        )
                        is UpdateUiState.Installing -> stringResource(R.string.update_banner_installing)
                        else -> uiText(R.string.about_update_available, UiText.Ltr(displayVersion(tag))).resolve()
                    },
                    style = SsType.Title,
                    color = SsColors.TextPrimary,
                )
                if (updateState is UpdateUiState.Failed) {
                    Text(
                        (updateState as UpdateUiState.Failed).reason.resolve(),
                        style = SsType.Body,
                        color = SsColors.TextBody,
                    )
                }
                val showBusyCaption = updateState == UpdateUiState.Busy ||
                    (updateState == UpdateUiState.Available && isBusy)
                if (showBusyCaption) {
                    Text(
                        stringResource(R.string.update_banner_busy),
                        style = SsType.Caption,
                        color = SsColors.TextSecondary,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    when (updateState) {
                        is UpdateUiState.Downloading -> {
                            SsButton(
                                stringResource(R.string.update_banner_cancel),
                                onClick = { selfUpdater.cancel() },
                                modifier = Modifier.focusRequester(primaryFocus),
                            )
                        }
                        is UpdateUiState.Installing -> {
                            // Nothing to press: the app closes and reopens on its own.
                        }
                        is UpdateUiState.Failed -> {
                            SsButton(
                                stringResource(R.string.update_banner_retry),
                                onClick = onUpdateNow,
                                modifier = Modifier.focusRequester(primaryFocus),
                            )
                            SsButton(stringResource(R.string.about_update_later), onClick = onLater)
                        }
                        else -> {
                            SsButton(
                                stringResource(R.string.update_banner_update_now),
                                onClick = onUpdateNow,
                                enabled = updateState == UpdateUiState.Available && !isBusy,
                                modifier = Modifier.focusRequester(primaryFocus),
                            )
                            SsButton(stringResource(R.string.about_update_later), onClick = onLater)
                        }
                    }
                }
            }
        }
    }
}
