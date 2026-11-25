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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import io.github.devjardins.sidespeed.R
import io.github.devjardins.sidespeed.apps.IconSource
import io.github.devjardins.sidespeed.tools.HomeCardState
import io.github.devjardins.sidespeed.tools.HomeResult
import io.github.devjardins.sidespeed.tools.HomeSwitcher
import io.github.devjardins.sidespeed.tools.homeCouldntChangeMessage
import io.github.devjardins.sidespeed.tools.Launcher
import io.github.devjardins.sidespeed.ui.apps.AppIcon
import io.github.devjardins.sidespeed.ui.text.UiText
import io.github.devjardins.sidespeed.ui.text.resolve
import io.github.devjardins.sidespeed.ui.text.uiText
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
    var status by remember { mutableStateOf<UiText?>(null) }
    var confirmTarget by remember { mutableStateOf<Launcher?>(null) }
    var confirmBack by remember { mutableStateOf(false) }
    var statusIsError by remember { mutableStateOf(false) }

    suspend fun refresh() {
        state = runCatching { switcher.cardState() }.getOrElse { state }
    }

    LaunchedEffect(Unit) { refresh() }

    suspend fun message(result: HomeResult, changed: Boolean): UiText = when (result) {
        is HomeResult.Ok -> UiText.Res(if (changed) R.string.tools_home_changed else R.string.tools_home_restored)
        is HomeResult.CouldntChange -> homeCouldntChangeMessage(
            runCatching { switcher.currentWithFallback()?.pkg }.getOrNull(),
        )
        is HomeResult.NoOriginal -> UiText.Res(R.string.tools_home_no_original)
        is HomeResult.StockDisabledElsewhere -> UiText.Res(R.string.tools_home_stock_disabled_elsewhere)
        is HomeResult.FireOs -> UiText.Res(R.string.tools_home_fire_os)
        is HomeResult.HelperDown -> UiText.Res(R.string.tools_helper_down)
        is HomeResult.HelperOutdated -> UiText.Res(R.string.tools_helper_outdated)
        is HomeResult.Refused -> UiText.Res(R.string.tools_home_refused)
        is HomeResult.Failed -> result.message
    }

    SsCard(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.tools_home_title), style = SsType.Title, color = SsColors.TextPrimary, modifier = Modifier.weight(1f))
                if (busy) SsSpinner()
            }
            when (val s = state) {
                null -> Text(stringResource(R.string.tools_home_checking), style = SsType.Body, color = SsColors.TextBody)
                is HomeCardState.FireOs -> Text(
                    stringResource(R.string.tools_home_fire_os),
                    style = SsType.Body,
                    color = SsColors.TextBody,
                )
                is HomeCardState.NoOtherLauncher -> {
                    CurrentHomeLine(s.current, switcher.launchers().firstOrNull { it.component == s.current }, icons)
                    Text(
                        stringResource(R.string.tools_home_no_other),
                        style = SsType.Body,
                        color = SsColors.TextBody,
                    )
                }
                is HomeCardState.Ready -> {
                    val currentLauncher = s.launchers.firstOrNull { it.component == s.current }
                    CurrentHomeLine(s.current, currentLauncher, icons)
                    if (s.drifted) {
                        Text(
                            stringResource(R.string.tools_home_drifted),
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
                                SsPill(stringResource(R.string.tools_home_current_pill), SsColors.OkPillBg, SsColors.OkPillText)
                            } else if (confirmTarget?.component != launcher.component) {
                                SsButton(
                                    stringResource(R.string.tools_home_make_home),
                                    onClick = { confirmTarget = launcher; confirmBack = false; status = null },
                                    enabled = !busy,
                                )
                            }
                        }
                        if (confirmTarget?.component == launcher.component) {
                            Text(
                                stringResource(R.string.tools_home_confirm_make, launcher.label),
                                style = SsType.Body,
                                color = SsColors.TextBody,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                SsButton(
                                    stringResource(R.string.tools_home_make_home),
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
                                    stringResource(R.string.tools_cancel),
                                    onClick = { confirmTarget = null },
                                    enabled = !busy,
                                )
                            }
                        }
                    }
                    if (s.original != null && !confirmBack) {
                        SsButton(
                            stringResource(R.string.tools_home_back_to_stock),
                            onClick = { confirmBack = true; confirmTarget = null; status = null },
                            enabled = !busy,
                        )
                    }
                    if (confirmBack) {
                        Text(
                            stringResource(R.string.tools_home_confirm_back),
                            style = SsType.Body,
                            color = SsColors.TextBody,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            SsButton(
                                stringResource(R.string.tools_home_back_to_stock),
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
                                stringResource(R.string.tools_cancel),
                                onClick = { confirmBack = false },
                                enabled = !busy,
                            )
                        }
                    }
                }
            }
            status?.let {
                Text(it.resolve(), style = SsType.Body, color = if (statusIsError) SsColors.DangerCardText else SsColors.TextBody)
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
                current == null -> stringResource(R.string.tools_home_current_unknown)
                launcher != null -> stringResource(R.string.tools_home_current, launcher.label)
                else -> uiText(R.string.tools_home_current, UiText.Ltr(current.pkg)).resolve()
            },
            style = SsType.Body,
            color = SsColors.TextBody,
        )
    }
}
