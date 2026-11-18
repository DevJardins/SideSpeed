package io.github.devjardins.sidespeed.ui.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.tv.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.devjardins.sidespeed.tools.Cleanup
import io.github.devjardins.sidespeed.tools.CleanupThreshold
import io.github.devjardins.sidespeed.tools.DeviceInfo
import io.github.devjardins.sidespeed.tools.StorageInfo
import io.github.devjardins.sidespeed.tools.ToolsStore
import io.github.devjardins.sidespeed.tools.formatBytes
import io.github.devjardins.sidespeed.tools.shortLabel
import io.github.devjardins.sidespeed.ui.theme.SsButton
import io.github.devjardins.sidespeed.ui.theme.SsCard
import io.github.devjardins.sidespeed.ui.theme.SsColors
import io.github.devjardins.sidespeed.ui.theme.SsSpinner
import io.github.devjardins.sidespeed.ui.theme.SsType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private fun lastCleanupLine(atMs: Long, freedBytes: Long?, skipped: String?): String {
    val time = SimpleDateFormat("EEE HH:mm", Locale.getDefault()).format(Date(atMs))
    val outcome = if (skipped != null) skipped else "freed ${formatBytes(freedBytes ?: 0)}"
    return "Last cleanup: $time · $outcome"
}

/**
 * Free-up-space card: storage summary, a Clean-up-now button, and the automatic-cleanup choice.
 * [onThresholdChanged] fires after the new threshold is stored so the host can start the service.
 */
@Composable
fun SpaceCard(
    store: ToolsStore,
    deviceInfo: DeviceInfo,
    cleanup: Cleanup,
    onThresholdChanged: () -> Unit = {},
) {
    val threshold by store.cleanupThreshold.collectAsState(initial = CleanupThreshold.OFF)
    val last by store.lastCleanup.collectAsState(initial = null)
    val scope = rememberCoroutineScope()
    var storage by remember { mutableStateOf<StorageInfo?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    // Plain remember: after a process recreation no trim is running, so the button must not stay "cleaning".
    var cleaning by remember { mutableStateOf(false) }
    // Button feedback: the freed amount (or skip reason) for a few seconds after a run.
    var flash by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(refresh) {
        storage = withContext(Dispatchers.IO) { runCatching { deviceInfo.storage() }.getOrNull() }
    }
    SsCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Free up space", style = SsType.Title, color = SsColors.TextPrimary)
            Text(
                storage?.let { "${formatBytes(it.freeBytes)} free of ${formatBytes(it.totalBytes)}" } ?: "…",
                style = SsType.Body,
                color = SsColors.TextSecondary,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                // Never disabled while running: extra presses are ignored and focus stays on the button.
                SsButton(
                    when {
                        cleaning -> "Cleaning…"
                        flash != null -> flash!!
                        else -> "Clean up now"
                    },
                    onClick = {
                        if (cleaning) return@SsButton
                        scope.launch {
                            cleaning = true
                            flash = null
                            val record = runCatching { cleanup.cleanNow() }.getOrNull()
                            refresh++
                            flash = record?.let { r ->
                                if (r.skipped != null) r.skipped else "Freed ${formatBytes(r.freedBytes ?: 0)}"
                            }
                            cleaning = false
                            delay(4_000)
                            flash = null
                        }
                    },
                )
                if (cleaning) SsSpinner()
            }
            Text("Automatic cleanup", style = SsType.Body, color = SsColors.TextPrimary)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CleanupThreshold.entries.forEach { t ->
                    SsButton(
                        t.shortLabel(),
                        onClick = {
                            scope.launch {
                                store.setCleanupThreshold(t)
                                onThresholdChanged()
                            }
                        },
                        selected = t == threshold,
                    )
                }
            }
            Text(
                "Clears app caches only when free space drops below this. Checked every few hours while the helper is running.",
                style = SsType.Caption,
                color = SsColors.TextSecondary,
            )
            val record = last
            Text(
                record?.let { lastCleanupLine(it.atMs, it.freedBytes, it.skipped) } ?: "Last cleanup: never",
                style = SsType.Caption,
                color = SsColors.TextSecondary,
            )
        }
    }
}
