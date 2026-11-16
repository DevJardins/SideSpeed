package io.github.devjardins.sidespeed.companion.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import kotlinx.coroutines.delay
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun HomeScreen(
    state: CompanionUiState,
    onRestart: (String?) -> Unit,
    onAdd: () -> Unit,
    onSettings: () -> Unit,
) {
    val now by produceState(System.currentTimeMillis()) {
        while (true) { delay(15_000); value = System.currentTimeMillis() }
    }
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val name = state.trustedNetworkName
        val banner = if (name != null) "Trusted Network: $name ✓" else "Not on a Trusted Network"
        Box(
            Modifier.fillMaxWidth().background(if (name != null) Ss.okBg else Ss.warnBg, RoundedCornerShape(12.dp)).padding(14.dp),
        ) { SsText(banner, Ss.body.copy(color = if (name != null) Ss.okText else Ss.warnText)) }

        state.error?.let { SsText(it, Ss.body.copy(color = Ss.dangerText), Modifier.fillMaxWidth().background(Ss.dangerBg, RoundedCornerShape(12.dp)).padding(14.dp)) }

        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(state.tvs, key = { it.id }) { tv ->
                Column(Modifier.fillMaxWidth().background(Ss.surface, RoundedCornerShape(14.dp)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    SsText(tv.name, Ss.heading)
                    SsText(tv.statusLabel)
                    tv.checkedAtEpochMs?.let { SsText(Copy.checkedAgo(now - it), Ss.caption) }
                    tv.note?.let { SsText(it, Ss.caption.copy(color = Ss.warnText)) }
                    SsButton("Restart now", { onRestart(tv.id) })
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SsButton("Add a TV", onAdd, primary = true)
            SsButton("Settings", onSettings)
        }
    }
}
