package io.github.devjardins.sidespeed.ui.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text

/** D-pad number grid for entering a code (digits only). The "5" key takes initial focus when asked. */
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
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("$label: ${value.ifEmpty { "-" }}", style = MaterialTheme.typography.titleMedium)
        val rows = listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("Del", "0", "Clr"))
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { key ->
                    val m = Modifier.width(88.dp).let { if (key == "1" && focusFirst) it.focusRequester(first) else it }
                    Button(
                        onClick = {
                            when (key) {
                                "Del" -> onValueChange(value.dropLast(1))
                                "Clr" -> onValueChange("")
                                else -> if (value.length < maxLength) onValueChange(value + key)
                            }
                        },
                        modifier = m,
                    ) { Text(key) }
                }
            }
        }
    }
}
