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
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import io.github.devjardins.sidespeed.tools.ManualCheckResult
import io.github.devjardins.sidespeed.tools.displayVersion
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
) {
    val ui by viewModel.state.collectAsState()
    val promptTag = ui.promptTag
    // Back closes the prompt first; only with no prompt does it leave the screen.
    BackHandler(enabled = promptTag == null, onBack = onBack)
    BackHandler(enabled = promptTag != null) { viewModel.dismissPrompt() }
    // Give the D-pad a starting point: the Back button takes focus when the screen opens.
    val backFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { backFocus.requestFocus() } }
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
                SsButton("‹ Back", onClick = onBack, modifier = Modifier.focusRequester(backFocus))
                Spacer(Modifier.width(16.dp))
                Text("About", style = SsType.Headline, color = SsColors.TextPrimary)
            }
            SsCard(Modifier.fillMaxWidth()) {
                Column(
                    Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("SideSpeed", style = SsType.Title, color = SsColors.TextPrimary)
                    Text("Version $versionName", style = SsType.Body, color = SsColors.TextBody)
                    SsButton(
                        "Check for updates",
                        onClick = { viewModel.checkNow() },
                        enabled = !ui.checking,
                    )
                    // A newer release opens the prompt below instead of a line here;
                    // UpToDate/Failed keep their inline text.
                    val line = when {
                        ui.checking -> "Checking…"
                        ui.result is ManualCheckResult.UpToDate -> "You have the latest version."
                        ui.result is ManualCheckResult.Failed -> "Couldn't reach GitHub. Try again later."
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
                    Text("Made with ♥ by DevJardins", style = SsType.Body, color = SsColors.TextBody)
                    Text("devjardins.com", style = SsType.Body, color = SsColors.TextBody)
                    Text(
                        "Support SideSpeed on Ko-fi",
                        style = SsType.Title,
                        color = SsColors.TextPrimary,
                    )
                    Text("ko-fi.com/devjardins", style = SsType.Body, color = SsColors.TextBody)
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
                            contentDescription = "QR code for ko-fi.com/devjardins",
                            modifier = Modifier.size(160.dp),
                        )
                    }
                    Text(
                        "Scan with your phone.",
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
                        is UpdateUiState.Downloading -> "Downloading… ${(updateState as UpdateUiState.Downloading).percent}%"
                        is UpdateUiState.Installing -> "Installing… SideSpeed will close and reopen."
                        else -> "SideSpeed ${displayVersion(tag)} is available"
                    },
                    style = SsType.Title,
                    color = SsColors.TextPrimary,
                )
                if (updateState is UpdateUiState.Failed) {
                    Text(
                        (updateState as UpdateUiState.Failed).reason,
                        style = SsType.Body,
                        color = SsColors.TextBody,
                    )
                }
                val showBusyCaption = updateState == UpdateUiState.Busy ||
                    (updateState == UpdateUiState.Available && isBusy)
                if (showBusyCaption) {
                    Text(
                        "Available when the current job finishes.",
                        style = SsType.Caption,
                        color = SsColors.TextSecondary,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    when (updateState) {
                        is UpdateUiState.Downloading -> {
                            SsButton(
                                "Cancel",
                                onClick = { selfUpdater.cancel() },
                                modifier = Modifier.focusRequester(primaryFocus),
                            )
                        }
                        is UpdateUiState.Installing -> {
                            // Nothing to press: the app closes and reopens on its own.
                        }
                        is UpdateUiState.Failed -> {
                            SsButton(
                                "Retry",
                                onClick = onUpdateNow,
                                modifier = Modifier.focusRequester(primaryFocus),
                            )
                            SsButton("Later", onClick = onLater)
                        }
                        else -> {
                            SsButton(
                                "Update now",
                                onClick = onUpdateNow,
                                enabled = updateState == UpdateUiState.Available && !isBusy,
                                modifier = Modifier.focusRequester(primaryFocus),
                            )
                            SsButton("Later", onClick = onLater)
                        }
                    }
                }
            }
        }
    }
}
