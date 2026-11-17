package io.github.devjardins.sidespeed.companion.ui

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
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
    /** TV card glows: SideSpeed green on the home network, Tailscale blue over the tailnet. */
    val glowLan = Color(SsTokens.GLOW_LAN)
    val glowTailnet = Color(SsTokens.GLOW_TAILNET)
    /** An unreachable card glows true red. */
    val glowUnreachable = Color(SsTokens.GLOW_UNREACHABLE)

    /** Phone type scale 24 / 18 / 15 / 13 sp. */
    val title = TextStyle(color = textPrimary, fontSize = 24.sp, fontWeight = FontWeight.Medium)
    val heading = TextStyle(color = textPrimary, fontSize = 18.sp, fontWeight = FontWeight.Medium)
    val body = TextStyle(color = textBody, fontSize = 15.sp)
    val caption = TextStyle(color = textSecondary, fontSize = 13.sp)
}

@Composable
fun SsText(
    text: String,
    style: TextStyle = Ss.body,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
) = BasicText(text, modifier, style, maxLines = maxLines, overflow = overflow)
