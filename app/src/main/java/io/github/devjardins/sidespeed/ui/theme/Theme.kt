package io.github.devjardins.sidespeed.ui.theme

import android.text.TextUtils
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Typography
import androidx.tv.material3.darkColorScheme
import io.github.devjardins.sidespeed.l10n.AppLanguage
import io.github.devjardins.sidespeed.theme.SsTokens

/** Dark-room palette (design Revision 4), values from [SsTokens]. No bright fills, no lime, never pure white. */
object SsColors {
    val Background = Color(SsTokens.BACKGROUND)
    val Surface = Color(SsTokens.SURFACE)
    val Button = Color(SsTokens.BUTTON)
    val Raised = Color(SsTokens.RAISED)
    val FocusButton = Color(SsTokens.FOCUS_BUTTON)
    val TextPrimary = Color(SsTokens.TEXT_PRIMARY)
    val TextBody = Color(SsTokens.TEXT_BODY)
    val TextSecondary = Color(SsTokens.TEXT_SECONDARY)
    val Accent = Color(SsTokens.ACCENT)
    val OkPillBg = Color(SsTokens.OK_PILL_BG)
    val OkPillText = Color(SsTokens.OK_PILL_TEXT)
    val WarnPillBg = Color(SsTokens.WARN_PILL_BG)
    val WarnPillText = Color(SsTokens.WARN_PILL_TEXT)
    val DangerCardBg = Color(SsTokens.DANGER_CARD_BG)
    val DangerCardText = Color(SsTokens.DANGER_CARD_TEXT)
}

/**
 * Type sizes (sp): headline 28, title 20, body 16, caption 13. No colours here, so text inside buttons follows
 * the button's focused/unfocused content colour; screens pass a colour where needed.
 */
object SsType {
    val Headline = TextStyle(fontSize = SsTokens.HEADLINE_SP.sp, lineHeight = 36.sp, fontWeight = FontWeight.Medium)
    val Title = TextStyle(fontSize = SsTokens.TITLE_SP.sp, lineHeight = 28.sp, fontWeight = FontWeight.Medium)
    val Body = TextStyle(fontSize = SsTokens.BODY_SP.sp, lineHeight = 24.sp)
    val Caption = TextStyle(fontSize = SsTokens.CAPTION_SP.sp, lineHeight = 18.sp)
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
    // The MainActivity root: layout follows the language actually shown, so a chosen
    // language that never shipped (or a device language with no translation) keeps
    // English text left to right. Debug ar-XB still maps to itself, so it stays RTL.
    val direction =
        if (TextUtils.getLayoutDirectionFromLocale(AppLanguage.effectiveLocale(LocalContext.current)) ==
            View.LAYOUT_DIRECTION_RTL
        ) {
            LayoutDirection.Rtl
        } else {
            LayoutDirection.Ltr
        }
    CompositionLocalProvider(LocalLayoutDirection provides direction) {
        MaterialTheme(colorScheme = colorScheme, typography = typography, content = content)
    }
}
