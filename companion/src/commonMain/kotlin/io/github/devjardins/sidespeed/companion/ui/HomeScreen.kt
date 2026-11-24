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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
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
import io.github.devjardins.sidespeed.companion.ui.text.resolve
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
    onAcknowledgeRestart: () -> Unit = {},
    onVisibleChange: (Boolean) -> Unit = {},
    onAcknowledge: (String) -> Unit = {},
    onPair: (String) -> Unit = {},
    onPort: (String, String) -> Unit = { _, _ -> },
    onSubmitPair: (String, String) -> Unit = { _, _ -> },
    onSubmitConnectPort: (String) -> Unit = {},
    onCancelPair: () -> Unit = {},
    onConfirm: (String) -> Unit = {},
) {
    DisposableEffect(Unit) {
        onVisibleChange(true)
        onDispose { onVisibleChange(false) }
    }
    val now by produceState(System.currentTimeMillis()) {
        while (true) { delay(15_000); value = System.currentTimeMillis() }
    }
    // The TV whose restart is in flight, and the finished-restart messages (shown briefly). The message
    // timeout lives in [RestartMessageHost], not in the effect below, so ending the in-flight state
    // can't cancel the clearing the way re-keying this effect did.
    var restartingId by remember { mutableStateOf<String?>(null) }
    val messageScope = rememberCoroutineScope()
    val messageHost = remember { RestartMessageHost(messageScope) }
    val messages by messageHost.messages.collectAsState()
    // The TV whose Remove is waiting for a second press, so one stray tap never deletes it.
    var confirmRemoveId by remember { mutableStateOf<String?>(null) }
    val latest by rememberUpdatedState(state)
    LaunchedEffect(restartingId) {
        val id = restartingId ?: return@LaunchedEffect
        delay(600)
        snapshotFlow { latest.busy }.first { !it }
        restartingId = null
        val outcome = latest.lastRestart?.takeIf { it.tvId == id }?.outcome
        messageHost.show(id, outcome?.takeIf { it == RestartOutcome.RESTARTED || it == RestartOutcome.NOT_CHECKED })
        onAcknowledgeRestart()
    }

    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (state.savedByNewerVersion) {
            SsText(
                Copy.OLDER_COPY.resolve(),
                Ss.body.copy(color = Ss.dangerText),
                Modifier.fillMaxWidth().background(Ss.dangerBg, RoundedCornerShape(12.dp)).padding(14.dp),
            )
        }
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
            SsText(it.resolve(), Ss.body.copy(color = Ss.dangerText), Modifier.fillMaxWidth().background(Ss.dangerBg, RoundedCornerShape(12.dp)).padding(14.dp))
        }

        if (state.tvs.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                SsCard {
                    SsText("No TVs yet", Ss.heading)
                    SsText("Add your TV. On Fire TV, Companion keeps SideSpeed's helper running. On Google TV it's optional and shows what's compiling.", Ss.caption)
                    Box(Modifier.padding(top = 8.dp)) { SsButton("Add a TV", onAdd, primary = true) }
                }
            }
        } else {
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(state.tvs, key = { it.id }) { tv ->
                    val restarting = restartingId == tv.id
                    val parked = tv.status == TvStatus.CHECK_FAILED
                    SsReachCard(cardGlow(tv.reach, tv.checkableHere, tv.attention, parked)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            SsStatusDot(cardDot(tv.status, tv.checkableHere, tv.attention, parked), pulsing = restarting)
                            SsText(tv.name, Ss.heading, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        SsText(tv.address, Ss.caption, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        SsText(if (restarting) "Restarting…" else if (tv.checkableHere) tv.statusLabel.resolve() else Copy.lastSeen(tv.statusLabel).resolve())
                        cardCheckedLine(tv.attention, tv.checkableHere, tv.checkedAtEpochMs, now, parked)
                            ?.let { SsText(it.resolve(), Ss.caption) }
                        tv.note?.let { SsText(it.resolve(), Ss.caption.copy(color = Ss.warnText)) }
                        tv.scopeNote?.let { SsText(it.resolve(), Ss.caption) }
                        TvActivityBlock(tv, now)
                        if (tv.attention != TvAttention.NONE) {
                            AttentionBlock(
                                tv, state.pairing, onAcknowledge, onPair, onRestart, onPort,
                                onSubmitPair, onSubmitConnectPort, onCancelPair, onConfirm,
                            )
                        }
                        if (TvCardAction.RESTART in cardActions(tv)) {
                            Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                SsButton(
                                    "Restart now",
                                    { restartingId = tv.id; onRestart(tv.id) },
                                    enabled = restartingId == null && tv.attention == TvAttention.NONE,
                                )
                                if (restarting) SsSpinner()
                                when (messages[tv.id]?.outcome) {
                                    RestartOutcome.RESTARTED -> SsText("Restarted ✓", Ss.body.copy(color = Ss.okText))
                                    RestartOutcome.NOT_CHECKED -> SsText(Copy.NOT_CHECKED_HERE.resolve(), Ss.body.copy(color = Ss.warnText))
                                    else -> Unit
                                }
                            }
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

@Composable
private fun AttentionBlock(
    tv: TvCardState,
    pairing: PairingForm?,
    onAcknowledge: (String) -> Unit,
    onPair: (String) -> Unit,
    onRestart: (String?) -> Unit,
    onPort: (String, String) -> Unit,
    onSubmitPair: (String, String) -> Unit,
    onSubmitConnectPort: (String) -> Unit,
    onCancelPair: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    val form = pairing?.takeIf { it.tvId == tv.id }
    if (form != null) {
        PairingBlock(form, onSubmitPair, onSubmitConnectPort, onCancelPair)
        return
    }
    when (tv.attention) {
        TvAttention.LEGACY_ACK -> {
            var checked by remember(tv.id) { mutableStateOf(false) }
            SsText(Copy.NOT_CONTACTED.resolve(), Ss.caption)
            SsText(Copy.CLASSIC_ACK.resolve(), Ss.caption)
            SsCheckRow(Copy.CLASSIC_ACK_CHECK.resolve(), checked) { checked = it }
            SsButton("Continue", { onAcknowledge(tv.id) }, enabled = checked)
        }
        TvAttention.LEGACY_PAIR -> {
            SsText(Copy.NOT_CONTACTED.resolve(), Ss.caption)
            SsText(Copy.PAIR_OFFER.resolve(), Ss.caption)
            SsButton(Copy.PAIR.resolve(), { onPair(tv.id) })
        }
        TvAttention.CONFIRM -> {
            SsText(Copy.CONFIRM_TV.resolve(), Ss.caption)
            SsButton(Copy.CONFIRM.resolve(), { onConfirm(tv.id) })
        }
        TvAttention.WIRELESS_OFF -> {
            var typing by remember(tv.id) { mutableStateOf(false) }
            var port by remember(tv.id) { mutableStateOf("") }
            SsText(Copy.WIRELESS_OFF.resolve(), Ss.caption)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SsButton(Copy.TRY_AGAIN.resolve(), { onRestart(tv.id) })
                if (!typing) SsButton(Copy.TYPE_THE_PORT.resolve(), { typing = true })
            }
            if (typing) {
                SsText(Copy.CONNECT_PORT_HINT.resolve(), Ss.caption)
                SsTextField(port, { port = it }, "37123")
                SsButton("Add", { onPort(tv.id, port) }, enabled = port.isNotBlank())
            }
        }
        TvAttention.NONE -> Unit
    }
}
