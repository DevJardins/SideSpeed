package io.github.devjardins.sidespeed.companion.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
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
    onRenameNetwork: (String, String) -> Unit,
    onRemoveNetwork: (String) -> Unit,
    onRemoveTailnet: (String) -> Unit,
    onAbout: () -> Unit,
    onBack: () -> Unit,
    wifiNameAccess: WifiNameAccess = WifiNameAccess.Unsupported,
    onWifiNameChange: (Boolean) -> Unit = {},
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
                var editing by remember(n.id) { mutableStateOf(false) }
                if (editing) {
                    var label by remember(n.id) { mutableStateOf(n.name) }
                    val focus = remember { FocusRequester() }
                    LaunchedEffect(Unit) { focus.requestFocus() }
                    val save = { onRenameNetwork(n.id, label); editing = false }
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SsTextField(label, { label = it }, n.id, Modifier.focusRequester(focus), ImeAction.Done, save)
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            SsButton("Save", save, primary = true)
                            SsButton("Cancel", { editing = false })
                        }
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        SsText(n.displayName, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        SsButton("Rename", { editing = true })
                        SsButton("Remove", { onRemoveNetwork(n.id) })
                    }
                }
            }
            if (state.currentNetwork != null) SsButton("Add as Trusted Network", onAddNetwork, modifier = Modifier.padding(top = 4.dp))
        }
        // Desktop only: Android has no Tailscale source, so `tailnet` is null there and none is ever trusted.
        if (state.tailnet != null || state.trustedOverlays.isNotEmpty()) SsCard {
            SsText("Tailnets", Ss.heading)
            SsText("TVs you add through Tailscale are kept alive wherever Tailscale is connected. Anyone who administers the tailnet can reach them.", Ss.caption)
            state.trustedOverlays.forEach { o ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    SsText(o.name + " · " + o.tailnet, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    SsButton("Remove", { onRemoveTailnet(o.id) })
                }
            }
            val t = state.tailnet
            if (t == null || !t.ready) SsText("Tailscale is not connected.", Ss.caption)
        }
        // Android only: the Wi-Fi name needs a permission the desktop never asks for.
        if (wifiNameAccess.supported) SsCard {
            SsSwitchRow(Copy.WIFI_NAME_TITLE, s.useWifiName, onWifiNameChange)
            SsText(Copy.WIFI_NAME_CAVEAT, Ss.caption)
            if (!s.useWifiName) SsText(Copy.WIFI_NAME_OFF, Ss.caption)
            if (!s.useWifiName && state.wifiNameGranted) SsText(Copy.WIFI_NAME_REVOKE, Ss.caption)
            if (state.wifiNameUnreadable) SsText(Copy.WIFI_NAME_UNREADABLE, Ss.caption)
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
