package io.github.devjardins.sidespeed.companion.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp

@Composable
fun AddTvScreen(state: CompanionUiState, onFind: () -> Unit, onAddAddress: (String) -> Unit, onBack: () -> Unit) {
    var address by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SsText("Add a TV", Ss.title)
        SsText(Copy.ALLOW_STEP, Ss.caption)
        Box(Modifier.fillMaxWidth().background(Ss.surface, RoundedCornerShape(12.dp)).padding(14.dp)) {
            BasicTextField(address, { address = it }, textStyle = Ss.body.copy(color = Ss.textPrimary), cursorBrush = SolidColor(Ss.accent), singleLine = true)
            if (address.isEmpty()) SsText("192.168.1.50", Ss.caption)
        }
        state.error?.let { SsText(it, Ss.body.copy(color = Ss.dangerText)) }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SsButton("Add", { onAddAddress(address) }, primary = true)
            SsButton("Find TVs", onFind)
            SsButton("Back", onBack)
        }
        state.found.forEach { host -> SsButton(host, { onAddAddress(host) }) }
    }
}
