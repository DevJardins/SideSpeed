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

@Composable
fun AddTvScreen(
    state: CompanionUiState,
    onFind: () -> Unit,
    onAddAddress: (String) -> Unit,
    onStop: () -> Unit,
    onKeepScanning: () -> Unit,
    onBack: () -> Unit,
) {
    var address by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Header("Add a TV", onBack = onBack)
        SsText(Copy.ALLOW_STEP, Ss.caption)
        SsTextField(address, { address = it }, "192.168.1.50")
        if (state.addErrors.isEmpty()) state.error?.let { SsText(it, Ss.body.copy(color = Ss.dangerText)) }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SsButton("Add", { onAddAddress(address) }, primary = true)
            SsButton(
                "Find TVs",
                { if (!state.scanning) onFind() },
                enabled = !state.scanning,
                modifier = Modifier.defaultMinSize(minWidth = 110.dp),
                content = if (state.scanning) ({ SsSpinner() }) else null,
            )
        }
        state.scanProgress?.let { SsText(it, Ss.caption) }
        if (state.scanning) {
            SsButton("Stop", onStop)
        } else if (state.scanStopped || state.diagnosis == Diagnosis.PARTIAL) {
            SsButton("Keep scanning", onKeepScanning)
        }
        if (!state.scanning) state.diagnosis?.let { SsText(helpFor(it), Ss.caption) }
        if (state.noneFound && !state.scanning) SsText(Copy.NO_TVS_FOUND, Ss.caption)
        state.found.forEach { host ->
            val adding = host in state.adding
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SsButton(
                    if (adding) "$host  Adding…" else host,
                    { if (!adding) onAddAddress(host) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !adding,
                )
                state.addErrors[host]?.let { SsText(it, Ss.caption.copy(color = Ss.dangerText)) }
            }
        }
        state.unsupported.forEach { host ->
            SsText("$host  ${Copy.TLS_UNSUPPORTED}", Ss.caption)
        }
    }
}
