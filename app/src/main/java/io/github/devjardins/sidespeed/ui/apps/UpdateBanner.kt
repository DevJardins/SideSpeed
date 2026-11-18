package io.github.devjardins.sidespeed.ui.apps

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import android.content.pm.ApplicationInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import io.github.devjardins.sidespeed.tools.ToolsStore
import io.github.devjardins.sidespeed.tools.UpdateCheck
import io.github.devjardins.sidespeed.tools.displayVersion
import io.github.devjardins.sidespeed.tools.shouldShowBanner
import io.github.devjardins.sidespeed.ui.theme.SsButton
import io.github.devjardins.sidespeed.ui.theme.SsCard
import io.github.devjardins.sidespeed.ui.theme.SsColors
import io.github.devjardins.sidespeed.ui.theme.SsType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Slot for the [AppsScreen] banner: runs the update check once per appearance and shows the
 * banner only when a newer release is known and the user hasn't dismissed that version.
 */
@Composable
fun UpdateBannerSlot(check: UpdateCheck, store: ToolsStore, currentVersion: String) {
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
        val version = effectiveLatest!!
        UpdateBanner(latest = displayVersion(version), onDismiss = {
            scope.launch { store.setUpdateDismissedVersion(version) }
        })
    }
}

/** "SideSpeed X is out" banner. No download here: Downloader is the only install path. */
@Composable
fun UpdateBanner(latest: String, onDismiss: () -> Unit) {
    SsCard(Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "SideSpeed $latest is out",
                    style = SsType.Body,
                    color = SsColors.TextPrimary,
                )
                Text(
                    "In Downloader, enter 3503400, or go to sidespeed.app/tv.",
                    style = SsType.Caption,
                    color = SsColors.TextSecondary,
                )
            }
            SsButton("Dismiss", onClick = onDismiss)
        }
    }
}
