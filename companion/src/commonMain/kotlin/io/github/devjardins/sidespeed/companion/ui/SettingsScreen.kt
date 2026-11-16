package io.github.devjardins.sidespeed.companion.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
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
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Header("Settings", onBack = onBack)
        SsCard {
            SsText("Trusted Networks", Ss.heading)
            state.trustedNetworks.forEach { n ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    SsText(n.name, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    SsButton("Remove", { onRemoveNetwork(n.id) })
                }
            }
            if (state.currentNetwork != null) SsButton("Add as Trusted Network", onAddNetwork, modifier = Modifier.padding(top = 4.dp))
        }
        SsCard {
            SsText("Notifications", Ss.heading)
            SsSwitchRow("Notify me when a TV needs attention", s.notifications) { onSettings(s.copy(notifications = it)) }
        }
        SsCard {
            SsText("Check every", Ss.heading)
            SsSegmented(listOf("15 minutes", "1 hour"), if (s.intervalMinutes == 60) 1 else 0) {
                onSettings(s.copy(intervalMinutes = if (it == 1) 60 else 15))
            }
            if (desktop) SsSwitchRow("Start when I log in", s.startAtLogin) { onSettings(s.copy(startAtLogin = it)) }
        }
        SsButton("About and licences", onAbout, modifier = Modifier.padding(bottom = 8.dp))
    }
}
