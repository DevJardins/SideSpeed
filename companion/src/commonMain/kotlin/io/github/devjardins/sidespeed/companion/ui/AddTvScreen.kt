package io.github.devjardins.sidespeed.companion.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.devjardins.sidespeed.companion.discovery.Diagnosis
import io.github.devjardins.sidespeed.companion.discovery.helpFor
import io.github.devjardins.sidespeed.companion.resources.Res
import io.github.devjardins.sidespeed.companion.resources.add_find_tvs
import io.github.devjardins.sidespeed.companion.resources.add_host_adding
import io.github.devjardins.sidespeed.companion.resources.add_keep_scanning
import io.github.devjardins.sidespeed.companion.resources.add_range_label
import io.github.devjardins.sidespeed.companion.resources.add_stop
import io.github.devjardins.sidespeed.companion.resources.common_add
import io.github.devjardins.sidespeed.companion.resources.common_cancel
import io.github.devjardins.sidespeed.companion.resources.home_add_tv
import io.github.devjardins.sidespeed.companion.ui.text.UiText
import io.github.devjardins.sidespeed.companion.ui.text.resolve
import org.jetbrains.compose.resources.stringResource

@Composable
fun AddTvScreen(
    state: CompanionUiState,
    onFind: (String) -> Unit,
    onAddAddress: (String, Boolean) -> Unit,
    onStop: () -> Unit,
    onKeepScanning: () -> Unit,
    onBack: () -> Unit,
    onClassicCheck: (Boolean) -> Unit,
    onStartPair: (String) -> Unit,
    onSubmitPair: (String, String) -> Unit,
    onSubmitConnectPort: (String) -> Unit,
    onCancelPair: () -> Unit,
) {
    var address by remember { mutableStateOf("") }
    var range by remember { mutableStateOf("") }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Header(stringResource(Res.string.home_add_tv), onBack = onBack)
        state.pairing?.let { form ->
            PairingBlock(form, onSubmitPair, onSubmitConnectPort, onCancelPair)
            return@Column
        }
        SsText(Copy.ALLOW_STEP.resolve(), Ss.caption)
        SsTextField(address, { address = it }, "192.168.1.50", ltr = true)
        if (state.addErrors.isEmpty()) state.error?.let { SsText(it.resolve(), Ss.body.copy(color = Ss.dangerText)) }
        val typedAck = state.classicAck?.takeIf { it.address == address.trim() }
        if (typedAck != null) ClassicAckBlock(typedAck, onClassicCheck)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SsButton(
                stringResource(Res.string.common_add),
                { onAddAddress(address, typedAck?.checked == true) },
                primary = true,
                enabled = typedAck?.checked != false,
            )
            SsButton(
                stringResource(Res.string.add_find_tvs),
                { if (!state.scanning) onFind(range) },
                enabled = !state.scanning,
                modifier = Modifier.defaultMinSize(minWidth = 110.dp),
                content = if (state.scanning) ({ SsSpinner() }) else null,
            )
        }
        SsText(stringResource(Res.string.add_range_label), Ss.caption)
        SsTextField(range, { range = it }, state.currentNetwork?.subnet ?: "192.168.1.0/24", ltr = true)
        state.scanProgress?.let { SsText(it.resolve(), Ss.caption) }
        if (state.scanning) {
            SsButton(stringResource(Res.string.add_stop), onStop)
        } else if (state.scanStopped || state.scanTruncated || state.diagnosis == Diagnosis.PARTIAL) {
            SsButton(stringResource(Res.string.add_keep_scanning), onKeepScanning)
        }
        state.found.forEach { host ->
            val adding = host in state.adding
            val ack = state.classicAck?.takeIf { it.address == host }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SsButton(
                    if (adding) UiText.Res(Res.string.add_host_adding, listOf(UiText.Ltr(host))).resolve() else UiText.Ltr(host).resolve(),
                    { if (!adding) onAddAddress(host, ack?.checked == true) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !adding && ack?.checked != false,
                )
                if (ack != null) ClassicAckBlock(ack, onClassicCheck)
                state.addErrors[host]?.let { SsText(it.resolve(), Ss.caption.copy(color = Ss.dangerText)) }
            }
        }
        state.tlsHosts.forEach { host ->
            SsButton(UiText.Ltr(host).resolve(), { onStartPair(host) }, modifier = Modifier.fillMaxWidth())
        }
        if (state.noneFound && !state.scanning) SsText(Copy.NO_TVS_FOUND.resolve(), Ss.caption)
        if (!state.scanning) state.diagnosis?.let { SsText(helpFor(it).resolve(), Ss.caption) }
    }
}

@Composable
private fun ClassicAckBlock(ack: ClassicAck, onCheck: (Boolean) -> Unit) {
    SsText(Copy.CLASSIC_ACK.resolve(), Ss.caption)
    SsCheckRow(Copy.CLASSIC_ACK_CHECK.resolve(), ack.checked, onCheck)
}

@Composable
fun PairingBlock(
    form: PairingForm,
    onSubmit: (String, String) -> Unit,
    onConnectPort: (String) -> Unit,
    onCancel: () -> Unit,
) {
    var code by remember(form.address) { mutableStateOf("") }
    var port by remember(form.address) { mutableStateOf("") }
    var connect by remember(form.address) { mutableStateOf("") }
    SsText(Copy.PAIR_STEPS.resolve(), Ss.caption)
    form.error?.let { SsText(it.resolve(), Ss.caption.copy(color = Ss.dangerText)) }
    if (!form.askConnectPort) {
        SsText(Copy.PAIR_CODE.resolve(), Ss.caption)
        SsTextField(code, { code = it }, "123456", ltr = true)
        SsText(Copy.PAIR_PORT.resolve(), Ss.caption)
        SsTextField(port, { port = it }, "37123", ltr = true)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SsButton(Copy.PAIR.resolve(), { onSubmit(code, port) }, primary = true)
            SsButton(stringResource(Res.string.common_cancel), onCancel)
        }
    } else {
        SsText(Copy.CONNECT_PORT_HINT.resolve(), Ss.caption)
        SsText(Copy.PAIR_PORT.resolve(), Ss.caption)
        SsTextField(connect, { connect = it }, "37123", ltr = true)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SsButton(stringResource(Res.string.common_add), { onConnectPort(connect) }, primary = true)
            SsButton(stringResource(Res.string.common_cancel), onCancel)
        }
    }
}
