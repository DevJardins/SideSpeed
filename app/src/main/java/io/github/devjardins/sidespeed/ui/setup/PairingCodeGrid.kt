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
import androidx.tv.material3.Text
import io.github.devjardins.sidespeed.ui.theme.SsButton
import io.github.devjardins.sidespeed.ui.theme.SsColors
import io.github.devjardins.sidespeed.ui.theme.SsType

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
        val rows = listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("Del", "0", "Clr"))
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { key ->
                    val m = Modifier.width(72.dp).let { if (key == "1" && focusFirst) it.focusRequester(first) else it }
                    SsButton(
                        onClick = {
                            when (key) {
                                "Del" -> onValueChange(value.dropLast(1))
                                "Clr" -> onValueChange("")
                                else -> if (value.length < maxLength) onValueChange(value + key)
                            }
                        },
                        modifier = m,
                        contentPadding = PaddingValues(vertical = 8.dp),
                    ) {
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { Text(key, style = SsType.Body) }
                    }
                }
            }
        }
    }
}
