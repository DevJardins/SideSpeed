package io.github.devjardins.sidespeed.companion.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp

@Composable
fun AddTvScreen(state: CompanionUiState, onFind: () -> Unit, onAddAddress: (String) -> Unit, onBack: () -> Unit) {
    var address by remember { mutableStateOf("") }
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val ring by animateColorAsState(if (focused) Ss.accent else Color.Transparent, tween(150))
    val shape = RoundedCornerShape(14.dp)
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Header("Add a TV", onBack = onBack)
        SsText(Copy.ALLOW_STEP, Ss.caption)
        Box(Modifier.fillMaxWidth().background(Ss.surface, shape).border(2.dp, ring, shape).padding(16.dp)) {
            BasicTextField(
                address, { address = it },
                Modifier.fillMaxWidth(),
                textStyle = Ss.body.copy(color = Ss.textPrimary),
                cursorBrush = SolidColor(Ss.accent),
                singleLine = true,
                interactionSource = source,
            )
            if (address.isEmpty()) SsText("192.168.1.50", Ss.caption)
        }
        state.error?.let { SsText(it, Ss.body.copy(color = Ss.dangerText)) }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SsButton("Add", { onAddAddress(address) }, primary = true)
            SsButton("Find TVs", onFind)
        }
        state.found.forEach { host -> SsButton(host, { onAddAddress(host) }, modifier = Modifier.fillMaxWidth()) }
    }
}
