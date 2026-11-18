package io.github.devjardins.sidespeed.ui.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import android.os.Build
import android.provider.Settings
import androidx.compose.ui.platform.LocalContext
import androidx.tv.material3.Text
import io.github.devjardins.sidespeed.tools.DeviceInfo
import io.github.devjardins.sidespeed.tools.DeviceSummary
import io.github.devjardins.sidespeed.tools.MemoryInfo
import io.github.devjardins.sidespeed.tools.StorageInfo
import io.github.devjardins.sidespeed.tools.formatBytes
import io.github.devjardins.sidespeed.ui.theme.SsCard
import io.github.devjardins.sidespeed.ui.theme.SsColors
import io.github.devjardins.sidespeed.ui.theme.SsType

/**
 * Slot for the Tools screen health card: a snapshot of the box, refreshed every time the
 * Tools screen opens. [helperUp] is the suspending helper ping (true = running).
 */
@Composable
fun HealthCardSlot(deviceInfo: DeviceInfo, helperUp: suspend () -> Boolean) {
    val context = LocalContext.current
    val summary = remember { deviceInfo.summary() }
    val memory = remember { deviceInfo.memory() }
    val storage = remember { deviceInfo.storage() }
    val deviceName = remember {
        runCatching {
            Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)
        }.getOrNull()
    }
    val helperRunning by produceState<Boolean?>(initialValue = null) {
        value = runCatching { helperUp() }.getOrNull()
    }
    HealthCard(
        summary = summary,
        memory = memory,
        storage = storage,
        helperRunning = helperRunning,
        deviceName = deviceName,
    )
}

/**
 * Marketing-friendly device label: the user's own device name when set, otherwise manufacturer
 * plus model (the manufacturer is skipped when the model already starts with it, so Fire TV
 * codes read "Amazon AFTMM" instead of a bare "AFTMM").
 */
fun deviceLabel(deviceName: String?, manufacturer: String, model: String): String {
    if (!deviceName.isNullOrBlank()) return deviceName.trim()
    val maker = manufacturer.trim().replaceFirstChar { it.uppercase() }
    val mdl = model.trim()
    if (maker.isEmpty()) return mdl
    if (mdl.startsWith(maker, ignoreCase = true)) return mdl
    return "$maker $mdl"
}

/** Box health at a glance: model, RAM, storage and helper status. No helper needed. */
@Composable
fun HealthCard(
    summary: DeviceSummary,
    memory: MemoryInfo,
    storage: StorageInfo,
    helperRunning: Boolean?,
    deviceName: String? = null,
) {
    SsCard(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("Box health", style = SsType.Title, color = SsColors.TextPrimary)
            Text(
                "${deviceLabel(deviceName, Build.MANUFACTURER, summary.model)} · Android ${summary.androidVersion}",
                style = SsType.Body,
                color = SsColors.TextBody,
            )
            Text(
                "${formatBytes(memory.availBytes)} free of ${formatBytes(memory.totalBytes)} RAM",
                style = SsType.Body,
                color = SsColors.TextBody,
            )
            Text(
                "${formatBytes(storage.freeBytes)} free of ${formatBytes(storage.totalBytes)} storage",
                style = SsType.Body,
                color = SsColors.TextBody,
            )
            Text(
                when (helperRunning) {
                    true -> "Helper: Running"
                    false -> "Helper: Not running"
                    null -> "Helper: …"
                },
                style = SsType.Body,
                color = SsColors.TextBody,
            )
        }
    }
}
