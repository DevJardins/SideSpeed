package io.github.devjardins.sidespeed.companion.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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

@Composable
fun AddTvScreen(state: CompanionUiState, onFind: () -> Unit, onAddAddress: (String) -> Unit, onBack: () -> Unit) {
    var address by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Header("Add a TV", onBack = onBack)
        SsText(Copy.ALLOW_STEP, Ss.caption)
        SsTextField(address, { address = it }, "192.168.1.50")
        state.error?.let { SsText(it, Ss.body.copy(color = Ss.dangerText)) }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SsButton("Add", { onAddAddress(address) }, primary = true)
            SsButton("Find TVs", onFind)
        }
        state.found.forEach { host -> SsButton(host, { onAddAddress(host) }, modifier = Modifier.fillMaxWidth()) }
    }
}
