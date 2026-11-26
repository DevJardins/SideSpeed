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
import androidx.compose.ui.res.stringResource
import androidx.tv.material3.Text
import io.github.devjardins.sidespeed.R
import io.github.devjardins.sidespeed.tools.DeviceInfo
import io.github.devjardins.sidespeed.tools.DeviceSummary
import io.github.devjardins.sidespeed.tools.MemoryInfo
import io.github.devjardins.sidespeed.tools.StorageInfo
import io.github.devjardins.sidespeed.tools.formatBytes
import io.github.devjardins.sidespeed.ui.text.UiText
import io.github.devjardins.sidespeed.ui.text.resolve
import io.github.devjardins.sidespeed.ui.text.uiText
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

/** "Living Room TV · Android 14". Each part is isolated so it stays whole in right-to-left languages. */
fun deviceLine(deviceName: String?, manufacturer: String, model: String, androidVersion: String): UiText =
    uiText(
        R.string.tools_health_device_line,
        UiText.Ltr(deviceLabel(deviceName, manufacturer, model)),
        UiText.Ltr("Android $androidVersion"),
    )

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
            Text(stringResource(R.string.tools_health_title), style = SsType.Title, color = SsColors.TextPrimary)
            Text(
                deviceLine(deviceName, Build.MANUFACTURER, summary.model, summary.androidVersion).resolve(),
                style = SsType.Body,
                color = SsColors.TextBody,
            )
            Text(
                uiText(R.string.tools_health_ram, formatBytes(memory.availBytes), formatBytes(memory.totalBytes)).resolve(),
                style = SsType.Body,
                color = SsColors.TextBody,
            )
            Text(
                uiText(R.string.tools_health_storage, formatBytes(storage.freeBytes), formatBytes(storage.totalBytes)).resolve(),
                style = SsType.Body,
                color = SsColors.TextBody,
            )
            Text(
                when (helperRunning) {
                    true -> stringResource(R.string.tools_health_helper_running)
                    false -> stringResource(R.string.tools_health_helper_not_running)
                    null -> stringResource(R.string.tools_health_helper_checking)
                },
                style = SsType.Body,
                color = SsColors.TextBody,
            )
        }
    }
}
