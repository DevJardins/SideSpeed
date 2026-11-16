package io.github.devjardins.sidespeed.companion.ui

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.devjardins.sidespeed.companion.store.CompanionSettings

@Composable
fun SettingsScreen(
    state: CompanionUiState,
    desktop: Boolean,
    onSettings: (CompanionSettings) -> Unit,
    onAddNetwork: () -> Unit,
    onRemoveNetwork: (String) -> Unit,
    onAbout: () -> Unit,
    onBack: () -> Unit,
) {
    val s = state.settings
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SsText("Settings", Ss.title)
        SsText("Trusted Networks", Ss.heading)
        state.trustedNetworks.forEach { n ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SsText(n.name, modifier = Modifier.weight(1f))
                SsButton("Remove", { onRemoveNetwork(n.id) })
            }
        }
        if (state.currentNetwork != null) SsButton("Add as Trusted Network", onAddNetwork)
        SsText("Notifications", Ss.heading)
        SsButton(if (s.notifications) "On" else "Off", { onSettings(s.copy(notifications = !s.notifications)) }, selected = s.notifications)
        SsText("Check every", Ss.heading)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SsButton("15 minutes", { onSettings(s.copy(intervalMinutes = 15)) }, selected = s.intervalMinutes == 15)
            SsButton("1 hour", { onSettings(s.copy(intervalMinutes = 60)) }, selected = s.intervalMinutes == 60)
        }
        if (desktop) SsButton("Start when I log in", { onSettings(s.copy(startAtLogin = !s.startAtLogin)) }, selected = s.startAtLogin)
        SsButton("About and licences", onAbout)
        SsButton("Back", onBack)
    }
}
