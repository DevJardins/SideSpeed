package io.github.devjardins.sidespeed.companion.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.devjardins.sidespeed.companion.store.TvStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

/** The Home banner: the current Trusted Network, else a trusted tailnet Tailscale is on; null when neither. */
fun trustBanner(state: CompanionUiState): String? =
    state.trustedNetworkName?.let { "Trusted Network: $it ✓" }
        ?: state.tailnet?.takeIf { it.trusted }?.let { "Trusted tailnet: ${it.name} ✓" }

@Composable
fun HomeScreen(
    state: CompanionUiState,
    onRestart: (String?) -> Unit,
    onAdd: () -> Unit,
    onSettings: () -> Unit,
    onRemove: (String) -> Unit = {},
) {
    val now by produceState(System.currentTimeMillis()) {
        while (true) { delay(15_000); value = System.currentTimeMillis() }
    }
    // The TV whose restart is in flight, and the one that just finished restarting (shown briefly).
    var restartingId by remember { mutableStateOf<String?>(null) }
    var restartedId by remember { mutableStateOf<String?>(null) }
    // The TV whose Remove is waiting for a second press, so one stray tap never deletes it.
    var confirmRemoveId by remember { mutableStateOf<String?>(null) }
    val latest by rememberUpdatedState(state)
    LaunchedEffect(restartingId) {
        val id = restartingId ?: return@LaunchedEffect
        delay(600)
        snapshotFlow { latest.busy }.first { !it }
        val ok = latest.tvs.firstOrNull { it.id == id }?.status == TvStatus.RUNNING
        restartingId = null
        if (ok) { restartedId = id; delay(2_000); if (restartedId == id) restartedId = null }
    }

    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SsLogoMark(30.dp)
            SsText("SideSpeed", Ss.title, Modifier.weight(1f))
            SsIconButton(onSettings, "Settings") { SsGlyph(Glyph.SETTINGS) }
        }
        val banner = trustBanner(state)
        val trusted = banner != null
        Row(
            Modifier.fillMaxWidth().background(if (trusted) Ss.okBg else Ss.warnBg, RoundedCornerShape(12.dp)).padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SsStatusDot(if (trusted) StatusKind.GOOD else StatusKind.WAITING)
            SsText(
                banner ?: "Not on a Trusted Network",
                Ss.body.copy(color = if (trusted) Ss.okText else Ss.warnText),
            )
        }

        state.error?.let {
            SsText(it, Ss.body.copy(color = Ss.dangerText), Modifier.fillMaxWidth().background(Ss.dangerBg, RoundedCornerShape(12.dp)).padding(14.dp))
        }

        if (state.tvs.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                SsCard {
                    SsText("No TVs yet", Ss.heading)
                    SsText("Add your Fire TV and SideSpeed Companion will keep its helper running.", Ss.caption)
                    Box(Modifier.padding(top = 8.dp)) { SsButton("Add a TV", onAdd, primary = true) }
                }
            }
        } else {
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(state.tvs, key = { it.id }) { tv ->
                    val restarting = restartingId == tv.id
                    SsCard {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            SsStatusDot(statusKind(tv.status), pulsing = restarting)
                            SsText(tv.name, Ss.heading, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        SsText(tv.address, Ss.caption, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        SsText(if (restarting) "Restarting…" else tv.statusLabel)
                        tv.checkedAtEpochMs?.let { SsText(Copy.checkedAgo(now - it), Ss.caption) }
                        tv.note?.let { SsText(it, Ss.caption.copy(color = Ss.warnText)) }
                        tv.scopeNote?.let { SsText(it, Ss.caption) }
                        Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            SsButton("Restart now", { restartingId = tv.id; onRestart(tv.id) }, enabled = restartingId == null)
                            if (restarting) SsSpinner()
                            if (restartedId == tv.id) SsText("Restarted ✓", Ss.body.copy(color = Ss.okText))
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            if (confirmRemoveId == tv.id) {
                                SsText("Remove ${tv.name}?", Ss.caption, Modifier.weight(1f))
                                SsButton("Remove", { confirmRemoveId = null; onRemove(tv.id) })
                                SsButton("Cancel", { confirmRemoveId = null })
                            } else {
                                SsButton("Remove TV", { confirmRemoveId = tv.id }, enabled = !restarting)
                            }
                        }
                    }
                }
            }
            SsButton("Add a TV", onAdd, primary = true, modifier = Modifier.fillMaxWidth())
        }
    }
}
