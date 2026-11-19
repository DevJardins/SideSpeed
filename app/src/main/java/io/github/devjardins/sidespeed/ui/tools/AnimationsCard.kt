package io.github.devjardins.sidespeed.ui.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import io.github.devjardins.sidespeed.tools.AnimationLevel
import io.github.devjardins.sidespeed.tools.Animations
import io.github.devjardins.sidespeed.tools.ToolResult
import io.github.devjardins.sidespeed.tools.matchLevel
import io.github.devjardins.sidespeed.tools.ToolsStore
import io.github.devjardins.sidespeed.ui.theme.SsButton
import io.github.devjardins.sidespeed.ui.theme.SsCard
import io.github.devjardins.sidespeed.ui.theme.SsColors
import io.github.devjardins.sidespeed.ui.theme.SsSpinner
import io.github.devjardins.sidespeed.ui.theme.SsType
import kotlinx.coroutines.launch

/** Slot for the Tools screen animations card. Reads the live scales when it opens. */
@Composable
fun AnimationsCard(animations: Animations, store: ToolsStore) {
    val originals by store.animationOriginals.collectAsState(initial = null)
    val scope = rememberCoroutineScope()
    var selected by remember { mutableStateOf<AnimationLevel?>(null) }
    var unreadable by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }

    suspend fun refreshSelection() {
        val current = animations.current().values.toList()
        unreadable = current.all { it == null }
        selected = matchLevel(current)
    }

    LaunchedEffect(Unit) {
        refreshSelection()
    }

    fun message(result: ToolResult<Unit>): String? = when (result) {
        is ToolResult.Ok -> null
        is ToolResult.HelperDown -> "Helper not running. Start the helper, then try again."
        is ToolResult.HelperOutdated -> "Restart the helper to use this."
        is ToolResult.Refused -> "The helper refused this setting."
        is ToolResult.Failed -> result.message
    }

    SsCard(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Animations", style = SsType.Title, color = SsColors.TextPrimary, modifier = Modifier.weight(1f))
                if (busy) SsSpinner()
            }
            Text(
                "Makes menus and screen changes move faster. It does not make apps load faster. " +
                    "Off removes animations completely; a few apps may look odd.",
                style = SsType.Body,
                color = SsColors.TextBody,
            )
            if (unreadable && status == null) {
                Text(
                    "Current values couldn't be read — the helper may not be running.",
                    style = SsType.Caption,
                    color = SsColors.TextSecondary,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                AnimationLevel.entries.forEach { level ->
                    val base = when (level) {
                        AnimationLevel.NORMAL -> "Normal (1×)"
                        AnimationLevel.FASTER -> "Faster (0.5×)"
                        AnimationLevel.OFF -> "Off"
                    }
                    // Same ✓-prefix convention as the Apps mode buttons: focus must never
                    // hide which option is active.
                    val label = if (selected == level) "✓ $base" else base
                    SsButton(
                        label,
                        onClick = {
                            busy = true
                            scope.launch {
                                status = message(animations.apply(level))
                                if (status == null) refreshSelection()
                                busy = false
                            }
                        },
                        enabled = !busy,
                        selected = selected == level,
                    )
                }
            }
            if (originals != null) {
                Text(
                    "Restores the values from before SideSpeed changed them.",
                    style = SsType.Caption,
                    color = SsColors.TextSecondary,
                )
                SsButton(
                    "Restore original",
                    onClick = {
                        busy = true
                        scope.launch {
                            status = message(animations.restore())
                            if (status == null) refreshSelection()
                            busy = false
                        }
                    },
                    enabled = !busy,
                )
            }
            status?.let {
                Text(it, style = SsType.Body, color = SsColors.DangerCardText)
            }
        }
    }
}
