package io.github.devjardins.sidespeed.ui.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.res.stringResource
import androidx.tv.material3.Text
import io.github.devjardins.sidespeed.R
import io.github.devjardins.sidespeed.ui.theme.SsButton
import io.github.devjardins.sidespeed.ui.theme.SsColors
import io.github.devjardins.sidespeed.ui.theme.SsType

private sealed interface KeypadKey {
    data class Digit(val digit: String) : KeypadKey
    data object Delete : KeypadKey
    data object Clear : KeypadKey
}

private val keypadRows = listOf(
    listOf(KeypadKey.Digit("1"), KeypadKey.Digit("2"), KeypadKey.Digit("3")),
    listOf(KeypadKey.Digit("4"), KeypadKey.Digit("5"), KeypadKey.Digit("6")),
    listOf(KeypadKey.Digit("7"), KeypadKey.Digit("8"), KeypadKey.Digit("9")),
    listOf(KeypadKey.Delete, KeypadKey.Digit("0"), KeypadKey.Clear),
)

/** D-pad number grid for entering a code (digits only). The "1" key takes initial focus when asked. */
@Composable
fun PairingCodeGrid(
    label: String,
    value: String,
    maxLength: Int,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    focusFirst: Boolean = false,
) {
    val first = remember { FocusRequester() }
    LaunchedEffect(focusFirst) { if (focusFirst) runCatching { first.requestFocus() } }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = SsType.Caption, color = SsColors.TextSecondary)
        Text(
            value.padEnd(maxLength, '·').toList().joinToString(" "),
            style = SsType.Title,
            color = SsColors.TextPrimary,
        )
        keypadRows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { key ->
                    val m = Modifier.width(72.dp).let {
                        if (key == KeypadKey.Digit("1") && focusFirst) it.focusRequester(first) else it
                    }
                    val text = when (key) {
                        is KeypadKey.Digit -> key.digit
                        KeypadKey.Delete -> stringResource(R.string.setup_keypad_del)
                        KeypadKey.Clear -> stringResource(R.string.setup_keypad_clr)
                    }
                    SsButton(
                        onClick = {
                            when (key) {
                                KeypadKey.Delete -> onValueChange(value.dropLast(1))
                                KeypadKey.Clear -> onValueChange("")
                                is KeypadKey.Digit -> if (value.length < maxLength) onValueChange(value + key.digit)
                            }
                        },
                        modifier = m,
                        contentPadding = PaddingValues(vertical = 8.dp),
                    ) {
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { Text(text, style = SsType.Body) }
                    }
                }
            }
        }
    }
}
