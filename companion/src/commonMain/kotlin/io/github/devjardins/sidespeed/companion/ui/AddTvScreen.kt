package io.github.devjardins.sidespeed.companion.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.devjardins.sidespeed.companion.discovery.Diagnosis
import io.github.devjardins.sidespeed.companion.discovery.helpFor
import io.github.devjardins.sidespeed.companion.ui.text.resolve

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
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Header("Add a TV", onBack = onBack)
        state.pairing?.let { form ->
            PairingBlock(form, onSubmitPair, onSubmitConnectPort, onCancelPair)
            return@Column
        }
        SsText(Copy.ALLOW_STEP.resolve(), Ss.caption)
        SsTextField(address, { address = it }, "192.168.1.50")
        if (state.addErrors.isEmpty()) state.error?.let { SsText(it.resolve(), Ss.body.copy(color = Ss.dangerText)) }
        val typedAck = state.classicAck?.takeIf { it.address == address.trim() }
        if (typedAck != null) ClassicAckBlock(typedAck, onClassicCheck)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SsButton(
                "Add",
                { onAddAddress(address, typedAck?.checked == true) },
                primary = true,
                enabled = typedAck?.checked != false,
            )
            SsButton(
                "Find TVs",
                { if (!state.scanning) onFind(range) },
                enabled = !state.scanning,
                modifier = Modifier.defaultMinSize(minWidth = 110.dp),
                content = if (state.scanning) ({ SsSpinner() }) else null,
            )
        }
        SsText("Range to scan (optional)", Ss.caption)
        SsTextField(range, { range = it }, state.currentNetwork?.subnet ?: "192.168.1.0/24")
        state.scanProgress?.let { SsText(it.resolve(), Ss.caption) }
        if (state.scanning) {
            SsButton("Stop", onStop)
        } else if (state.scanStopped || state.scanTruncated || state.diagnosis == Diagnosis.PARTIAL) {
            SsButton("Keep scanning", onKeepScanning)
        }
        state.found.forEach { host ->
            val adding = host in state.adding
            val ack = state.classicAck?.takeIf { it.address == host }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SsButton(
                    if (adding) "$host  Adding…" else host,
                    { if (!adding) onAddAddress(host, ack?.checked == true) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !adding && ack?.checked != false,
                )
                if (ack != null) ClassicAckBlock(ack, onClassicCheck)
                state.addErrors[host]?.let { SsText(it.resolve(), Ss.caption.copy(color = Ss.dangerText)) }
            }
        }
        state.tlsHosts.forEach { host ->
            SsButton(host, { onStartPair(host) }, modifier = Modifier.fillMaxWidth())
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
        SsTextField(code, { code = it }, "123456")
        SsText(Copy.PAIR_PORT.resolve(), Ss.caption)
        SsTextField(port, { port = it }, "37123")
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SsButton(Copy.PAIR.resolve(), { onSubmit(code, port) }, primary = true)
            SsButton("Cancel", onCancel)
        }
    } else {
        SsText(Copy.CONNECT_PORT_HINT.resolve(), Ss.caption)
        SsText(Copy.PAIR_PORT.resolve(), Ss.caption)
        SsTextField(connect, { connect = it }, "37123")
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SsButton("Add", { onConnectPort(connect) }, primary = true)
            SsButton("Cancel", onCancel)
        }
    }
}
