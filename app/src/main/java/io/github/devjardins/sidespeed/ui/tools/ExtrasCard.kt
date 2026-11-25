package io.github.devjardins.sidespeed.ui.tools

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import io.github.devjardins.sidespeed.ui.theme.SsButton
import io.github.devjardins.sidespeed.ui.theme.SsCard
import io.github.devjardins.sidespeed.ui.theme.SsColors
import io.github.devjardins.sidespeed.ui.theme.SsType

@Composable
fun ExtrasCard(onOpen: () -> Unit, openFocus: FocusRequester = remember { FocusRequester() }) {
    SsCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
            Text("Turn off extras", style = SsType.Title, color = SsColors.TextPrimary)
            Text(
                "Turn off apps your TV maker added that you don't use. You can turn them back on any time.",
                style = SsType.Body,
                color = SsColors.TextBody,
                modifier = Modifier.padding(top = 8.dp),
            )
            SsButton("Open", onClick = onOpen, modifier = Modifier.padding(top = 12.dp).focusRequester(openFocus))
        }
    }
}