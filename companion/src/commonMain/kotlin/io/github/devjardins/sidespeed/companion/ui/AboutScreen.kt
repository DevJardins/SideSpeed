package io.github.devjardins.sidespeed.companion.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.devjardins.sidespeed.companion.update.CompanionUpdateState
import io.github.devjardins.sidespeed.companion.update.displayVersion

/**
 * The About screen: version, a manual update check, and the licences. The check runs when the
 * screen opens and when its button is pressed; there is no background polling. The platform's
 * [updateAction] slot renders below the status line ("Update now" on Android, "Download" on
 * desktop), or nothing when there is nothing to act on.
 */
@Composable
fun AboutScreen(
    aboutText: String,
    versionName: String,
    update: CompanionUpdateState,
    onCheckUpdates: () -> Unit,
    onBack: () -> Unit,
    updateAction: @Composable () -> Unit,
) {
    LaunchedEffect(Unit) { onCheckUpdates() }
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Header("About and licences", onBack = onBack)
        SsCard {
            SsText("SideSpeed Companion", Ss.title)
            if (versionName.isNotEmpty()) SsText("Version $versionName")
            SsButton(
                "Check for updates",
                onClick = onCheckUpdates,
                enabled = update != CompanionUpdateState.Checking &&
                    update !is CompanionUpdateState.Downloading &&
                    update != CompanionUpdateState.Installing,
            )
            // A newer release shows its line plus the platform's action below; UpToDate/Failed
            // keep their inline text, matching the TV About screen's style.
            val line = when (update) {
                CompanionUpdateState.Idle -> null
                CompanionUpdateState.Checking -> "Checking…"
                CompanionUpdateState.UpToDate -> "You have the latest version."
                is CompanionUpdateState.Available -> "SideSpeed Companion ${displayVersion(update.tag)} is available."
                is CompanionUpdateState.Downloading -> "Downloading… ${update.percent}%"
                CompanionUpdateState.Installing -> "Installing…"
                is CompanionUpdateState.Failed -> update.reason
            }
            line?.let { SsText(it) }
            updateAction()
        }
        SsCard { SsText(aboutText) }
    }
}
