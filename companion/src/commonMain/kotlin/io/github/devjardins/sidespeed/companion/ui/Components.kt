package io.github.devjardins.sidespeed.companion.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

private const val CLICK_DEBOUNCE_MS = 300L

/** A calm button: press, hover and focus all show; focus and selection draw a sage ring. */
@Composable
fun SsButton(
    label: String,
    onClick: () -> Unit,
    primary: Boolean = false,
    selected: Boolean = false,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    /** Shown in place of [label] when given (e.g. a spinner); [label] is then only the fallback text. */
    content: (@Composable () -> Unit)? = null,
) {
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val hovered by source.collectIsHoveredAsState()
    val pressed by source.collectIsPressedAsState()
    val base = if (primary) Ss.raised else Ss.button
    val fill by animateColorAsState(
        when {
            pressed -> Ss.raised
            focused || hovered -> Ss.focusButton
            else -> base
        },
        tween(120),
    )
    val ring by animateColorAsState(if (focused || selected) Ss.accent else Color.Transparent, tween(150))
    val scale by animateFloatAsState(if (pressed && enabled) 0.97f else 1f, tween(100))
    var lastClick by remember { mutableStateOf(0L) }
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier
            .graphicsLayer { scaleX = scale; scaleY = scale; alpha = if (enabled) 1f else 0.5f }
            .background(fill, shape)
            .border(2.dp, ring, shape)
            .hoverable(source)
            .clickable(interactionSource = source, indication = null, enabled = enabled) {
                val now = System.currentTimeMillis()
                if (now - lastClick >= CLICK_DEBOUNCE_MS) {
                    lastClick = now
                    onClick()
                }
            }
            .focusable(enabled, source)
            .padding(PaddingValues(horizontal = 18.dp, vertical = 12.dp)),
        contentAlignment = Alignment.Center,
    ) {
        if (content != null) content() else SsText(label, Ss.body.copy(color = if (primary) Ss.accent else Ss.textPrimary))
    }
}

/** A soft card: surface fill, hairline border, generous rounding. */
@Composable
fun SsCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier.fillMaxWidth().background(Ss.surface, shape).border(1.dp, Ss.textPrimary.copy(alpha = 0.08f), shape).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        content = content,
    )
}

/** A round icon-sized hit target with the same press, hover and focus feedback as [SsButton]. */
@Composable
fun SsIconButton(onClick: () -> Unit, contentDescription: String, content: @Composable () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val hovered by source.collectIsHoveredAsState()
    val pressed by source.collectIsPressedAsState()
    val fill by animateColorAsState(if (pressed) Ss.raised else if (focused || hovered) Ss.focusButton else Color.Transparent, tween(120))
    val ring by animateColorAsState(if (focused) Ss.accent else Color.Transparent, tween(150))
    val scale by animateFloatAsState(if (pressed) 0.92f else 1f, tween(100))
    var lastClick by remember { mutableStateOf(0L) }
    Box(
        Modifier.size(40.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .background(fill, CircleShape).border(2.dp, ring, CircleShape)
            .hoverable(source)
            .clickable(interactionSource = source, indication = null, onClickLabel = contentDescription) {
                val now = System.currentTimeMillis()
                if (now - lastClick >= CLICK_DEBOUNCE_MS) { lastClick = now; onClick() }
            }
            .focusable(true, source),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** Label on the left, an animated pill switch on the right. */
@Composable
fun SsSwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val hovered by source.collectIsHoveredAsState()
    val track by animateColorAsState(if (checked) Ss.accent.copy(alpha = 0.55f) else Ss.raised, tween(160))
    val knob by animateDpAsState(if (checked) 20.dp else 2.dp, tween(160))
    val ring by animateColorAsState(if (focused) Ss.accent else Color.Transparent, tween(150))
    val rowShape = RoundedCornerShape(12.dp)
    Row(
        Modifier.fillMaxWidth()
            .background(if (hovered) Ss.focusButton.copy(alpha = 0.5f) else Color.Transparent, rowShape)
            .border(2.dp, ring, rowShape)
            .hoverable(source)
            .clickable(interactionSource = source, indication = null) { onChange(!checked) }
            .focusable(true, source)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SsText(label, Ss.body.copy(color = Ss.textPrimary), Modifier.weight(1f))
        Box(Modifier.size(width = 44.dp, height = 24.dp).background(track, CircleShape)) {
            Box(Modifier.offset(x = knob, y = 2.dp).size(20.dp).background(Ss.textPrimary, CircleShape))
        }
    }
}

/** A row of options with one selected; the choice is shown by an accent-tinted pill. */
@Composable
fun SsSegmented(options: List<String>, selectedIndex: Int, onSelect: (Int) -> Unit) {
    Row(
        Modifier.background(Ss.button, RoundedCornerShape(12.dp)).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEachIndexed { i, label ->
            val source = remember { MutableInteractionSource() }
            val focused by source.collectIsFocusedAsState()
            val hovered by source.collectIsHoveredAsState()
            val on = i == selectedIndex
            val fill by animateColorAsState(
                if (on) Ss.accent.copy(alpha = 0.22f) else if (hovered || focused) Ss.focusButton else Color.Transparent, tween(150),
            )
            val ring by animateColorAsState(if (focused) Ss.accent else Color.Transparent, tween(150))
            val shape = RoundedCornerShape(9.dp)
            Box(
                Modifier.background(fill, shape).border(2.dp, ring, shape)
                    .hoverable(source)
                    .clickable(interactionSource = source, indication = null) { onSelect(i) }
                    .focusable(true, source)
                    .padding(horizontal = 16.dp, vertical = 9.dp),
            ) { SsText(label, Ss.body.copy(color = if (on) Ss.accent else Ss.textSecondary)) }
        }
    }
}

/** A small status dot; it pulses while [pulsing]. */
@Composable
fun SsStatusDot(kind: StatusKind, pulsing: Boolean = false) {
    val colour = when (kind) {
        StatusKind.GOOD -> Ss.okText
        StatusKind.WAITING -> Ss.warnText
        StatusKind.BAD -> Ss.dangerText
        StatusKind.UNKNOWN -> Ss.textSecondary
    }
    // The infinite transition only exists while pulsing, so idle dots never keep the frame clock running.
    val alpha = if (pulsing) {
        val pulse = rememberInfiniteTransition(label = "dot")
        val a by pulse.animateFloat(1f, 0.35f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "alpha")
        a
    } else 1f
    Box(Modifier.size(10.dp).alpha(alpha).background(colour, CircleShape))
}

/** A small rotating arc for work in progress. */
@Composable
fun SsSpinner(size: Dp = 16.dp) {
    val t = rememberInfiniteTransition(label = "spin")
    val angle by t.animateFloat(0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing)), label = "angle")
    val colour = Ss.accent
    Canvas(Modifier.size(size).rotate(angle)) {
        val w = this.size.minDimension * 0.14f
        drawArc(colour, 0f, 270f, false, topLeft = Offset(w / 2, w / 2), size = Size(this.size.width - w, this.size.height - w), style = Stroke(w, cap = StrokeCap.Round))
    }
}

/** The app mark: the robot logo, a rounded square, at the size it is called with. */
@Composable
fun SsLogoMark(size: Dp) {
    Image(
        painter = rememberSsLogoPainter(),
        contentDescription = "SideSpeed",
        modifier = Modifier.size(size).clip(RoundedCornerShape(percent = 28)),
        contentScale = ContentScale.Crop,
    )
}

/** A small gear-less settings glyph (three sliders) and a back chevron, drawn so there is no icon dependency. */
@Composable
fun SsGlyph(kind: Glyph) {
    val colour = Ss.textPrimary
    Canvas(Modifier.size(20.dp)) {
        val s = this.size.minDimension
        val st = Stroke(s * 0.1f, cap = StrokeCap.Round)
        when (kind) {
            Glyph.SETTINGS -> listOf(0.25f to 0.35f, 0.5f to 0.65f, 0.75f to 0.45f).forEach { (y, x) ->
                drawLine(colour, Offset(s * 0.1f, s * y), Offset(s * 0.9f, s * y), s * 0.1f, StrokeCap.Round)
                drawCircle(Ss.accent, s * 0.12f, Offset(s * x, s * y))
            }
            Glyph.BACK -> drawPath(
                androidx.compose.ui.graphics.Path().apply { moveTo(s * 0.62f, s * 0.2f); lineTo(s * 0.32f, s * 0.5f); lineTo(s * 0.62f, s * 0.8f) },
                colour, style = st,
            )
        }
    }
}

enum class Glyph { SETTINGS, BACK }
