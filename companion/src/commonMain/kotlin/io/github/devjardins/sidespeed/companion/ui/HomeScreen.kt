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
import androidx.compose.foundation.border
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.devjardins.sidespeed.companion.resources.Res
import io.github.devjardins.sidespeed.companion.resources.brand_sidespeed
import io.github.devjardins.sidespeed.companion.resources.common_add
import io.github.devjardins.sidespeed.companion.resources.common_cancel
import io.github.devjardins.sidespeed.companion.resources.common_continue
import io.github.devjardins.sidespeed.companion.resources.common_remove
import io.github.devjardins.sidespeed.companion.resources.home_add_tv
import io.github.devjardins.sidespeed.companion.resources.home_empty_body
import io.github.devjardins.sidespeed.companion.resources.home_empty_title
import io.github.devjardins.sidespeed.companion.resources.home_drop_on_card
import io.github.devjardins.sidespeed.companion.resources.home_install_app
import io.github.devjardins.sidespeed.companion.resources.home_not_trusted
import io.github.devjardins.sidespeed.companion.resources.home_optimize_now
import io.github.devjardins.sidespeed.companion.resources.home_remove_confirm
import io.github.devjardins.sidespeed.companion.resources.home_remove_tv
import io.github.devjardins.sidespeed.companion.resources.home_restart_now
import io.github.devjardins.sidespeed.companion.resources.home_restarted
import io.github.devjardins.sidespeed.companion.resources.home_restarting
import io.github.devjardins.sidespeed.companion.resources.home_trust_banner
import io.github.devjardins.sidespeed.companion.resources.home_trust_tailnet_banner
import io.github.devjardins.sidespeed.companion.resources.settings_title
import io.github.devjardins.sidespeed.companion.store.TvStatus
import io.github.devjardins.sidespeed.companion.ui.text.UiText
import io.github.devjardins.sidespeed.companion.ui.text.resolve
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import org.jetbrains.compose.resources.stringResource

/**
 * Lets a platform make Home accept dropped app files. Both calls are composable so the platform can remember
 * its own drop handlers. Only cards that can take an install right now are given [card], and [home] gets the
 * ids of those cards (empty while an install is running).
 */
interface InstallDropHook {
    /** The drop target for one card that can take an install now. */
    @Composable fun card(tvId: String): Modifier

    /** The drop target for the rest of Home: a file dropped outside every card lands here. */
    @Composable fun home(installableTvIds: Set<String>): Modifier
}

/** How long an install or optimize notice stays on a card. */
private const val NOTICE_MS = 8_000L

/**
 * Failure notices a TV card can show: [Copy.OPTIMIZE_FAILED] from a failed optimize run, and
 * [Copy.NOT_TRUSTED_ERROR] when either an install or an optimize run finds the TV is not on a
 * Trusted Network. Every other notice the card shows ([Copy.installed], [Copy.OPTIMIZE_STARTED],
 * [Copy.OPTIMIZE_CAUGHT_UP]) reports success.
 */
private val ERROR_NOTICES: Set<UiText> = setOf(Copy.OPTIMIZE_FAILED, Copy.NOT_TRUSTED_ERROR)

/** True when [notice] is a failure, shown in the error colour; success notices stay green. */
fun noticeIsError(notice: UiText): Boolean = notice in ERROR_NOTICES

/** The Home banner: the current Trusted Network, else a trusted tailnet Tailscale is on; null when neither. */
fun trustBanner(state: CompanionUiState): UiText? =
    state.trustedNetworkName?.let { UiText.Res(Res.string.home_trust_banner, listOf(UiText.Fsi(it))) }
        ?: state.tailnet?.takeIf { it.trusted }?.let { UiText.Res(Res.string.home_trust_tailnet_banner, listOf(UiText.Fsi(it.name))) }

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
    onInstall: (String) -> Unit = {},
    onOptimize: (String) -> Unit = {},
    onDismissInstallNotice: (String) -> Unit = {},
    dropHighlightTvId: String? = null,
    dropHintVisible: Boolean = false,
    installDrop: InstallDropHook? = null,
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

    // Cards that can take a dropped file right now: none while an install is already running.
    val installableIds = if (state.install != null) emptySet()
    else state.tvs.filter { TvCardAction.INSTALL in cardActions(it) }.mapTo(mutableSetOf()) { it.id }
    val homeDrop = installDrop?.home(installableIds) ?: Modifier
    Column(Modifier.fillMaxSize().then(homeDrop).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (state.savedByNewerVersion) {
            SsText(
                Copy.OLDER_COPY.resolve(),
                Ss.body.copy(color = Ss.dangerText),
                Modifier.fillMaxWidth().background(Ss.dangerBg, RoundedCornerShape(12.dp)).padding(14.dp),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SsLogoMark(30.dp)
            SsText(stringResource(Res.string.brand_sidespeed), Ss.title, Modifier.weight(1f))
            SsIconButton(onSettings, stringResource(Res.string.settings_title)) { SsGlyph(Glyph.SETTINGS) }
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
                banner?.resolve() ?: stringResource(Res.string.home_not_trusted),
                Ss.body.copy(color = if (trusted) Ss.okText else Ss.warnText),
            )
        }

        state.error?.let {
            SsText(it.resolve(), Ss.body.copy(color = Ss.dangerText), Modifier.fillMaxWidth().background(Ss.dangerBg, RoundedCornerShape(12.dp)).padding(14.dp))
        }

        if (state.tvs.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                SsCard {
                    SsText(stringResource(Res.string.home_empty_title), Ss.heading)
                    SsText(stringResource(Res.string.home_empty_body), Ss.caption)
                    Box(Modifier.padding(top = 8.dp)) {
                        SsButton(stringResource(Res.string.home_add_tv), onAdd, primary = true)
                    }
                }
            }
        } else {
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(state.tvs, key = { it.id }) { tv ->
                    val restarting = restartingId == tv.id
                    val parked = tv.status == TvStatus.CHECK_FAILED
                    val cardDrop = if (installDrop != null && tv.id in installableIds) installDrop.card(tv.id) else Modifier
                    val highlight = dropHighlightTvId == tv.id
                    state.installNotice[tv.id]?.let { notice ->
                        LaunchedEffect(notice) { delay(NOTICE_MS); onDismissInstallNotice(tv.id) }
                    }
                    SsReachCard(
                        cardGlow(tv.reach, tv.checkableHere, tv.attention, parked),
                        cardDrop
                            .then(if (highlight) Modifier.border(2.dp, Ss.accent, RoundedCornerShape(16.dp)) else Modifier),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            SsStatusDot(cardDot(tv.status, tv.checkableHere, tv.attention, parked), pulsing = restarting)
                            SsText(tv.name, Ss.heading, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        SsText(UiText.Ltr(tv.address).resolve(), Ss.caption, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        SsText(
                            if (restarting) stringResource(Res.string.home_restarting)
                            else if (tv.checkableHere) tv.statusLabel.resolve()
                            else Copy.lastSeen(tv.statusLabel).resolve(),
                        )
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
                                    stringResource(Res.string.home_restart_now),
                                    { restartingId = tv.id; onRestart(tv.id) },
                                    enabled = restartingId == null && tv.attention == TvAttention.NONE,
                                )
                                if (restarting) SsSpinner()
                                when (messages[tv.id]?.outcome) {
                                    RestartOutcome.RESTARTED ->
                                        SsText(stringResource(Res.string.home_restarted), Ss.body.copy(color = Ss.okText))
                                    RestartOutcome.NOT_CHECKED ->
                                        SsText(Copy.NOT_CHECKED_HERE.resolve(), Ss.body.copy(color = Ss.warnText))
                                    else -> Unit
                                }
                            }
                        }
                        val actions = cardActions(tv)
                        val optimizing = tv.id in state.optimizingTvIds
                        if (TvCardAction.INSTALL in actions || TvCardAction.OPTIMIZE in actions) {
                            Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                if (TvCardAction.INSTALL in actions) {
                                    SsButton(stringResource(Res.string.home_install_app), { onInstall(tv.id) }, enabled = state.install == null)
                                }
                                if (TvCardAction.OPTIMIZE in actions) {
                                    SsButton(stringResource(Res.string.home_optimize_now), { onOptimize(tv.id) }, enabled = !optimizing)
                                    if (optimizing) SsSpinner()
                                }
                            }
                        }
                        if (highlight || dropHintVisible && tv.id in installableIds) {
                            SsText(stringResource(Res.string.home_drop_on_card), Ss.caption.copy(color = if (highlight) Ss.accent else Ss.textSecondary))
                        }
                        state.installNotice[tv.id]?.let { SsText(it.resolve(), Ss.body.copy(color = if (noticeIsError(it)) Ss.dangerText else Ss.okText)) }
                        state.optimizeNotice[tv.id]?.let { SsText(it.resolve(), Ss.body.copy(color = if (noticeIsError(it)) Ss.dangerText else Ss.okText)) }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            if (confirmRemoveId == tv.id) {
                                SsText(
                                    UiText.Res(Res.string.home_remove_confirm, listOf(UiText.Fsi(tv.name))).resolve(),
                                    Ss.caption,
                                    Modifier.weight(1f),
                                )
                                SsButton(stringResource(Res.string.common_remove), { confirmRemoveId = null; onRemove(tv.id) })
                                SsButton(stringResource(Res.string.common_cancel), { confirmRemoveId = null })
                            } else {
                                SsButton(stringResource(Res.string.home_remove_tv), { confirmRemoveId = tv.id }, enabled = !restarting)
                            }
                        }
                    }
                }
            }
            SsButton(stringResource(Res.string.home_add_tv), onAdd, primary = true, modifier = Modifier.fillMaxWidth())
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
            SsButton(stringResource(Res.string.common_continue), { onAcknowledge(tv.id) }, enabled = checked)
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
                SsTextField(port, { port = it }, "37123", ltr = true)
                SsButton(stringResource(Res.string.common_add), { onPort(tv.id, port) }, enabled = port.isNotBlank())
            }
        }
        TvAttention.NONE -> Unit
    }
}
