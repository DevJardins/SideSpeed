package io.github.devjardins.sidespeed.companion.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp

/** The single-line field used across screens: sage ring while focused, [placeholder] while empty. */
@Composable
fun SsTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    imeAction: ImeAction = ImeAction.Default,
    onDone: (() -> Unit)? = null,
) {
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val ring by animateColorAsState(if (focused) Ss.accent else Color.Transparent, tween(150))
    val shape = RoundedCornerShape(14.dp)
    Box(modifier.fillMaxWidth().background(Ss.surface, shape).border(2.dp, ring, shape).padding(16.dp)) {
        BasicTextField(
            value, onValueChange,
            Modifier.fillMaxWidth(),
            textStyle = Ss.body.copy(color = Ss.textPrimary),
            cursorBrush = SolidColor(Ss.accent),
            singleLine = true,
            interactionSource = source,
            keyboardOptions = KeyboardOptions(imeAction = imeAction),
            keyboardActions = KeyboardActions(onDone = { onDone?.invoke() }),
        )
        if (value.isEmpty()) SsText(placeholder, Ss.caption)
    }
}
