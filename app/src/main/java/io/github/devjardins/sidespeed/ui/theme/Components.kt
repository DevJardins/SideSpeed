package io.github.devjardins.sidespeed.ui.theme

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text

val CardShape = RoundedCornerShape(12.dp)
val ButtonShape = RoundedCornerShape(10.dp)
val PillShape = RoundedCornerShape(50)

private val focusRing @Composable get() = Border(BorderStroke(SsDimens.FocusRing, SsColors.Accent), shape = ButtonShape)

/** The screen frame: background colour and overscan-safe padding. */
@Composable
fun SsScreen(content: @Composable BoxScope.() -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(SsColors.Background)
            .padding(horizontal = SsDimens.ScreenH, vertical = SsDimens.ScreenV),
        content = content,
    )
}

/**
 * The app's button: resting [SsColors.Button], focused [SsColors.FocusButton] with [SsColors.TextPrimary] text and
 * a 2dp sage ring. [selected] marks the active tab or mode with sage text.
 */
@Composable
fun SsButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    selected: Boolean = false,
    contentPadding: PaddingValues = PaddingValues(horizontal = 20.dp, vertical = 10.dp),
    content: @Composable RowScope.() -> Unit,
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = ButtonDefaults.shape(shape = ButtonShape),
        scale = ButtonDefaults.scale(focusedScale = 1.03f),
        colors = ButtonDefaults.colors(
            containerColor = SsColors.Button,
            contentColor = if (selected) SsColors.Accent else SsColors.TextBody,
            focusedContainerColor = SsColors.FocusButton,
            focusedContentColor = if (selected) SsColors.Accent else SsColors.TextPrimary,
            pressedContainerColor = SsColors.FocusButton,
            pressedContentColor = SsColors.TextPrimary,
            disabledContainerColor = SsColors.Button.copy(alpha = 0.5f),
            disabledContentColor = SsColors.TextSecondary,
        ),
        border = ButtonDefaults.border(
            border = Border.None,
            focusedBorder = focusRing,
            pressedBorder = focusRing,
        ),
        glow = ButtonDefaults.glow(),
        contentPadding = contentPadding,
        content = content,
    )
}

@Composable
fun SsButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, selected: Boolean = false) {
    SsButton(onClick = onClick, modifier = modifier, enabled = enabled, selected = selected) {
        Text(label, style = SsType.Body)
    }
}

/** A clickable row: [SsColors.Surface] at rest, [SsColors.Raised] with the sage ring when focused. */
@Composable
fun SsRow(onClick: () -> Unit, modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = ClickableSurfaceDefaults.shape(shape = CardShape),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.01f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = SsColors.Surface,
            contentColor = SsColors.TextBody,
            focusedContainerColor = SsColors.Raised,
            focusedContentColor = SsColors.TextPrimary,
            pressedContainerColor = SsColors.Raised,
            pressedContentColor = SsColors.TextPrimary,
        ),
        border = ClickableSurfaceDefaults.border(
            border = Border.None,
            focusedBorder = Border(BorderStroke(SsDimens.FocusRing, SsColors.Accent), shape = CardShape),
            pressedBorder = Border(BorderStroke(SsDimens.FocusRing, SsColors.Accent), shape = CardShape),
        ),
        content = content,
    )
}

/** A non-focusable card. */
@Composable
fun SsCard(
    modifier: Modifier = Modifier,
    container: Color = SsColors.Surface,
    content: Color = SsColors.TextBody,
    body: @Composable BoxScope.() -> Unit,
) {
    Surface(
        modifier = modifier,
        shape = CardShape,
        colors = SurfaceDefaults.colors(containerColor = container, contentColor = content),
        content = body,
    )
}

@Composable
fun SsPill(label: String, background: Color, text: Color, modifier: Modifier = Modifier) {
    Box(modifier.background(background, PillShape).padding(horizontal = 12.dp, vertical = 4.dp)) {
        Text(label, style = SsType.Caption, color = text)
    }
}

/** A small sage spinner (tv-material has no progress indicator). */
@Composable
fun SsSpinner(size: Dp = 20.dp, color: Color = SsColors.Accent) {
    val transition = rememberInfiniteTransition(label = "spinner")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(1100, easing = LinearEasing), RepeatMode.Restart),
        label = "angle",
    )
    Canvas(Modifier.size(size)) {
        val stroke = 2.5.dp.toPx()
        drawArc(
            color = color.copy(alpha = 0.25f), startAngle = 0f, sweepAngle = 360f, useCenter = false,
            style = Stroke(stroke),
        )
        drawArc(
            color = color, startAngle = angle, sweepAngle = 100f, useCenter = false,
            style = Stroke(stroke, cap = StrokeCap.Round),
        )
    }
}
