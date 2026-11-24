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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import io.github.devjardins.sidespeed.R
import io.github.devjardins.sidespeed.tools.AnimationLevel
import io.github.devjardins.sidespeed.tools.Animations
import io.github.devjardins.sidespeed.tools.ToolResult
import io.github.devjardins.sidespeed.tools.ToolsStore
import io.github.devjardins.sidespeed.ui.text.UiText
import io.github.devjardins.sidespeed.ui.text.resolve
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
    var status by remember { mutableStateOf<UiText?>(null) }

    suspend fun refreshSelection() {
        val current = animations.current()
        unreadable = current.values.all { it == null }
        selected = animations.matchCurrent(current)
    }

    LaunchedEffect(Unit) {
        refreshSelection()
    }

    fun message(result: ToolResult<Unit>): UiText? = when (result) {
        is ToolResult.Ok -> null
        is ToolResult.HelperDown -> UiText.Res(R.string.tools_helper_down)
        is ToolResult.HelperOutdated -> UiText.Res(R.string.tools_helper_outdated)
        is ToolResult.Refused -> UiText.Res(R.string.tools_animations_refused)
        is ToolResult.Failed -> UiText.Raw(result.message)
    }

    SsCard(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.tools_animations_title), style = SsType.Title, color = SsColors.TextPrimary, modifier = Modifier.weight(1f))
                if (busy) SsSpinner()
            }
            Text(
                stringResource(R.string.tools_animations_body),
                style = SsType.Body,
                color = SsColors.TextBody,
            )
            if (unreadable && status == null) {
                Text(
                    stringResource(R.string.tools_animations_unreadable),
                    style = SsType.Caption,
                    color = SsColors.TextSecondary,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                AnimationLevel.entries.forEach { level ->
                    val base = stringResource(
                        when (level) {
                            AnimationLevel.NORMAL -> R.string.tools_animations_normal
                            AnimationLevel.FASTER -> R.string.tools_animations_faster
                            AnimationLevel.OFF -> R.string.tools_animations_off
                        },
                    )
                    // Same ✓-prefix convention as the Apps mode buttons: focus must never
                    // hide which option is active.
                    val label = if (selected == level) stringResource(R.string.tools_selected, base) else base
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
            if (animations.isFireOs) {
                Text(
                    stringResource(R.string.tools_animations_fire_tv_note),
                    style = SsType.Caption,
                    color = SsColors.TextSecondary,
                )
            }
            if (originals != null) {
                Text(
                    stringResource(R.string.tools_animations_restore_note),
                    style = SsType.Caption,
                    color = SsColors.TextSecondary,
                )
                SsButton(
                    stringResource(R.string.tools_animations_restore),
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
                Text(it.resolve(), style = SsType.Body, color = SsColors.DangerCardText)
            }
        }
    }
}
