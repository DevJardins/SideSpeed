package io.github.devjardins.sidespeed.companion.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.focusable
import io.github.devjardins.sidespeed.theme.SsTokens

object Ss {
    val background = Color(SsTokens.BACKGROUND)
    val surface = Color(SsTokens.SURFACE)
    val button = Color(SsTokens.BUTTON)
    val raised = Color(SsTokens.RAISED)
    val focusButton = Color(SsTokens.FOCUS_BUTTON)
    val textPrimary = Color(SsTokens.TEXT_PRIMARY)
    val textBody = Color(SsTokens.TEXT_BODY)
    val textSecondary = Color(SsTokens.TEXT_SECONDARY)
    val accent = Color(SsTokens.ACCENT)
    val okBg = Color(SsTokens.OK_PILL_BG)
    val okText = Color(SsTokens.OK_PILL_TEXT)
    val warnBg = Color(SsTokens.WARN_PILL_BG)
    val warnText = Color(SsTokens.WARN_PILL_TEXT)
    val dangerBg = Color(SsTokens.DANGER_CARD_BG)
    val dangerText = Color(SsTokens.DANGER_CARD_TEXT)

    /** Phone type scale 24 / 18 / 15 / 13 sp. */
    val title = TextStyle(color = textPrimary, fontSize = 24.sp, fontWeight = FontWeight.Medium)
    val heading = TextStyle(color = textPrimary, fontSize = 18.sp, fontWeight = FontWeight.Medium)
    val body = TextStyle(color = textBody, fontSize = 15.sp)
    val caption = TextStyle(color = textSecondary, fontSize = 13.sp)
}

@Composable
fun SsText(text: String, style: TextStyle = Ss.body, modifier: Modifier = Modifier) =
    BasicText(text, modifier, style)

/** A calm button: focus and selection show in sage. */
@Composable
fun SsButton(label: String, onClick: () -> Unit, primary: Boolean = false, selected: Boolean = false, modifier: Modifier = Modifier) {
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val shape = RoundedCornerShape(12.dp)
    val ring = if (focused || selected) Ss.accent else Color.Transparent
    Box(
        modifier
            .background(if (focused) Ss.focusButton else if (primary) Ss.raised else Ss.button, shape)
            .border(2.dp, ring, shape)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .focusable(interactionSource = source)
            .padding(PaddingValues(horizontal = 18.dp, vertical = 12.dp)),
    ) {
        SsText(label, Ss.body.copy(color = if (primary) Ss.accent else Ss.textPrimary))
    }
}
