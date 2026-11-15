package io.github.devjardins.sidespeed.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Typography
import androidx.tv.material3.darkColorScheme

/** Dark-room palette (design Revision 4). No bright fills, no lime, never pure white. */
object SsColors {
    val Background = Color(0xFF10151E)
    val Surface = Color(0xFF171E29)
    val Button = Color(0xFF1A222E)
    val Raised = Color(0xFF222C38)
    val FocusButton = Color(0xFF2A3644)
    val TextPrimary = Color(0xFFEEF1F5)
    val TextBody = Color(0xFFD9DEE6)
    val TextSecondary = Color(0xFF8C96A4)
    val Accent = Color(0xFF93BFA2)
    val OkPillBg = Color(0xFF1D2B24)
    val OkPillText = Color(0xFF9FC9AC)
    val WarnPillBg = Color(0xFF2A2620)
    val WarnPillText = Color(0xFFC9B48F)
    val DangerCardBg = Color(0xFF2E1F22)
    val DangerCardText = Color(0xFFE3B1B1)
}

/**
 * Type sizes (sp): headline 28, title 20, body 16, caption 13. No colours here, so text inside buttons follows
 * the button's focused/unfocused content colour; screens pass a colour where needed.
 */
object SsType {
    val Headline = TextStyle(fontSize = 28.sp, lineHeight = 36.sp, fontWeight = FontWeight.Medium)
    val Title = TextStyle(fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.Medium)
    val Body = TextStyle(fontSize = 16.sp, lineHeight = 24.sp)
    val Caption = TextStyle(fontSize = 13.sp, lineHeight = 18.sp)
}

/** Spacing. [ScreenH] keeps content inside the overscan-safe area. */
object SsDimens {
    val ScreenH = 48.dp
    val ScreenV = 32.dp
    val FocusRing = 2.dp
    val Icon = 36.dp
}

private val colorScheme = darkColorScheme(
    primary = SsColors.Accent,
    onPrimary = SsColors.Background,
    secondary = SsColors.Accent,
    background = SsColors.Background,
    onBackground = SsColors.TextBody,
    surface = SsColors.Surface,
    onSurface = SsColors.TextBody,
    surfaceVariant = SsColors.Button,
    onSurfaceVariant = SsColors.TextSecondary,
    border = SsColors.Accent,
    error = SsColors.DangerCardText,
    errorContainer = SsColors.DangerCardBg,
    onErrorContainer = SsColors.DangerCardText,
)

private val typography = Typography(
    displaySmall = SsType.Headline,
    headlineLarge = SsType.Headline,
    headlineMedium = SsType.Headline,
    headlineSmall = SsType.Title,
    titleLarge = SsType.Title,
    titleMedium = SsType.Title,
    titleSmall = SsType.Body,
    bodyLarge = SsType.Body,
    bodyMedium = SsType.Body,
    bodySmall = SsType.Caption,
    labelLarge = SsType.Body,
    labelMedium = SsType.Caption,
    labelSmall = SsType.Caption,
)

@Composable
fun SideSpeedTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colorScheme, typography = typography, content = content)
}
