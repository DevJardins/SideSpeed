package io.github.devjardins.sidespeed.companion.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.devjardins.sidespeed.companion.net.NetworkFingerprint

@Composable
fun FirstRunScreen(network: NetworkFingerprint?, onTrust: () -> Unit, onNotNow: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(28.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp, Alignment.CenterVertically),
    ) {
        SsLogoMark(56.dp)
        SsText("Choose your Trusted Network", Ss.title)
        SsText("SideSpeed Companion only works on networks you choose. It never connects or scans anywhere else.")
        if (network != null) SsCard { SsText("Router ${network.gatewayIp} · ${network.subnet}", Ss.caption) }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (network != null) SsButton("Trust this network", onTrust, primary = true)
            SsButton("Not now", onNotNow)
        }
    }
}
