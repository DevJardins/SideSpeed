package io.github.devjardins.sidespeed.ui.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import io.github.devjardins.sidespeed.apps.IconSource
import io.github.devjardins.sidespeed.tools.HomeCardState
import io.github.devjardins.sidespeed.tools.HomeResult
import io.github.devjardins.sidespeed.tools.HomeSwitcher
import io.github.devjardins.sidespeed.tools.Launcher
import io.github.devjardins.sidespeed.ui.apps.AppIcon
import io.github.devjardins.sidespeed.ui.theme.SsButton
import io.github.devjardins.sidespeed.ui.theme.SsCard
import io.github.devjardins.sidespeed.ui.theme.SsColors
import io.github.devjardins.sidespeed.ui.theme.SsPill
import io.github.devjardins.sidespeed.ui.theme.SsSpinner
import io.github.devjardins.sidespeed.ui.theme.SsType
import kotlinx.coroutines.launch

/** Tools card for the Home screen switcher. All reads are in-app; writes go through the helper. */
@Composable
fun HomeCard(switcher: HomeSwitcher, icons: IconSource) {
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<HomeCardState?>(null) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var confirmTarget by remember { mutableStateOf<Launcher?>(null) }
    var confirmBack by remember { mutableStateOf(false) }
    var statusIsError by remember { mutableStateOf(false) }

    suspend fun refresh() {
        state = runCatching { switcher.cardState() }.getOrElse { state }
    }

    LaunchedEffect(Unit) { refresh() }

    fun message(result: HomeResult, changed: Boolean): String = when (result) {
        is HomeResult.Ok -> if (changed) {
            "Home screen changed. Your remote's Home button may still open the previous home screen on some TVs."
        } else {
            "Back to your first home screen."
        }
        is HomeResult.CouldntChange -> "Couldn't change Home"
        is HomeResult.NoOriginal -> "No earlier home screen was saved."
        is HomeResult.StockDisabledElsewhere ->
            "Your first home screen was turned off by something else, so SideSpeed can't turn it back on."
        is HomeResult.FireOs -> "Fire TV doesn't allow other home screens."
        is HomeResult.HelperDown -> "Helper not running. Start the helper, then try again."
        is HomeResult.HelperOutdated -> "Restart the helper to use this."
        is HomeResult.Refused -> "The helper refused this change."
        is HomeResult.Failed -> result.message
    }

    SsCard(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Home screen", style = SsType.Title, color = SsColors.TextPrimary, modifier = Modifier.weight(1f))
                if (busy) SsSpinner()
            }
            when (val s = state) {
                null -> Text("Checking…", style = SsType.Body, color = SsColors.TextBody)
                is HomeCardState.FireOs -> Text(
                    "Fire TV doesn't allow other home screens.",
                    style = SsType.Body,
                    color = SsColors.TextBody,
                )
                is HomeCardState.NoOtherLauncher -> {
                    CurrentHomeLine(s.current, switcher.launchers().firstOrNull { it.component == s.current }, icons)
                    Text(
                        "Install another home screen app first, then come back here.",
                        style = SsType.Body,
                        color = SsColors.TextBody,
                    )
                }
                is HomeCardState.Ready -> {
                    val currentLauncher = s.launchers.firstOrNull { it.component == s.current }
                    CurrentHomeLine(s.current, currentLauncher, icons)
                    if (s.drifted) {
                        Text(
                            "Your home screen changed back on its own. The first home screen is still saved below.",
                            style = SsType.Caption,
                            color = SsColors.TextSecondary,
                        )
                    }
                    s.launchers.forEach { launcher ->
                        val isCurrent = launcher.component == s.current
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            AppIcon(launcher.component.pkg, icons)
                            Text(
                                launcher.label,
                                style = SsType.Body,
                                color = SsColors.TextBody,
                                modifier = Modifier.weight(1f),
                            )
                            if (isCurrent) {
                                SsPill("Current", SsColors.OkPillBg, SsColors.OkPillText)
                            } else if (confirmTarget?.component != launcher.component) {
                                SsButton(
                                    "Make this Home",
                                    onClick = { confirmTarget = launcher; confirmBack = false; status = null },
                                    enabled = !busy,
                                )
                            }
                        }
                        if (confirmTarget?.component == launcher.component) {
                            Text(
                                "Make ${launcher.label} your home screen?",
                                style = SsType.Body,
                                color = SsColors.TextBody,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                SsButton(
                                    "Make this Home",
                                    onClick = {
                                        busy = true
                                        scope.launch {
                                            val result = switcher.makeHome(launcher.component)
                                            statusIsError = result !is HomeResult.Ok
                                            status = message(result, changed = true)
                                            confirmTarget = null
                                            refresh()
                                            busy = false
                                        }
                                    },
                                    enabled = !busy,
                                )
                                SsButton(
                                    "Cancel",
                                    onClick = { confirmTarget = null },
                                    enabled = !busy,
                                )
                            }
                        }
                    }
                    if (s.original != null && !confirmBack) {
                        SsButton(
                            "Back to stock",
                            onClick = { confirmBack = true; confirmTarget = null; status = null },
                            enabled = !busy,
                        )
                    }
                    if (confirmBack) {
                        Text(
                            "Go back to the home screen this TV had before SideSpeed changed it?",
                            style = SsType.Body,
                            color = SsColors.TextBody,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            SsButton(
                                "Back to stock",
                                onClick = {
                                    busy = true
                                    scope.launch {
                                        val result = switcher.backToStock()
                                        statusIsError = result !is HomeResult.Ok
                                        status = message(result, changed = false)
                                        confirmBack = false
                                        refresh()
                                        busy = false
                                    }
                                },
                                enabled = !busy,
                            )
                            SsButton(
                                "Cancel",
                                onClick = { confirmBack = false },
                                enabled = !busy,
                            )
                        }
                    }
                }
            }
            status?.let {
                Text(it, style = SsType.Body, color = if (statusIsError) SsColors.DangerCardText else SsColors.TextBody)
            }
        }
    }
}

@Composable
private fun CurrentHomeLine(
    current: io.github.devjardins.sidespeed.tools.HomeComponent?,
    launcher: Launcher?,
    icons: IconSource,
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (current != null) AppIcon(current.pkg, icons)
        Text(
            when {
                current == null -> "Current Home: unknown"
                launcher != null -> "Current Home: ${launcher.label}"
                else -> "Current Home: ${current.pkg}"
            },
            style = SsType.Body,
            color = SsColors.TextBody,
        )
    }
}
