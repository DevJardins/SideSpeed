package io.github.devjardins.sidespeed.ui.tools

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import io.github.devjardins.sidespeed.tools.Extras
import io.github.devjardins.sidespeed.tools.ExtrasIndex
import io.github.devjardins.sidespeed.tools.ExtrasListMeta
import io.github.devjardins.sidespeed.tools.ExtrasMatchResult
import io.github.devjardins.sidespeed.tools.ExtrasRow
import io.github.devjardins.sidespeed.tools.ExtrasRowKind
import io.github.devjardins.sidespeed.tools.ExtrasRunStop
import io.github.devjardins.sidespeed.tools.HomeSwitcher
import io.github.devjardins.sidespeed.tools.ToolResult
import io.github.devjardins.sidespeed.tools.ToolsCommands
import io.github.devjardins.sidespeed.tools.formatRunOutcome
import io.github.devjardins.sidespeed.tools.upstreamCommitShort
import io.github.devjardins.sidespeed.ui.theme.SsButton
import io.github.devjardins.sidespeed.ui.theme.SsColors
import io.github.devjardins.sidespeed.ui.theme.SsPill
import io.github.devjardins.sidespeed.ui.theme.SsScreen
import io.github.devjardins.sidespeed.ui.theme.SsSpinner
import io.github.devjardins.sidespeed.ui.theme.SsType
import io.github.devjardins.sidespeed.ui.theme.bleed
import kotlinx.coroutines.launch

@Composable
fun ExtrasScreen(
    extras: Extras,
    index: ExtrasIndex,
    commands: ToolsCommands,
    homeSwitcher: HomeSwitcher,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val scope = rememberCoroutineScope()
    val backFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { backFocus.requestFocus() } }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    var variantIndex by remember { mutableIntStateOf(0) }
    var rows by remember { mutableStateOf<List<ExtrasRow>>(emptyList()) }
    var activeMeta by remember { mutableStateOf<ExtrasListMeta?>(null) }
    val checked = remember { mutableStateMapOf<String, Boolean>() }
    var confirmHome by remember { mutableStateOf<ExtrasRow?>(null) }
    var restoreEnabled by remember { mutableStateOf(false) }
    var homeLabelForConfirm by remember { mutableStateOf<String?>(null) }

    val match = remember(refresh) {
        extras.match(Build.MANUFACTURER, Build.MODEL, Build.DEVICE)
    }

    LaunchedEffect(match, variantIndex, refresh) {
        when (val m = match) {
            is ExtrasMatchResult.Matched -> {
                val meta = m.variants.getOrNull(variantIndex) ?: m.variants.first()
                activeMeta = meta
                val disabled = (commands.disabledPackages() as? ToolResult.Ok)?.value.orEmpty()
                rows = extras.rows(meta, disabled)
                rows.filter { it.selectable }.forEach { row ->
                    if (row.entry.pkg !in checked) checked[row.entry.pkg] = row.checked
                }
                restoreEnabled = extras.recordedCount() > 0
            }
            else -> {
                activeMeta = null
                rows = emptyList()
                restoreEnabled = false
            }
        }
    }

    LaunchedEffect(confirmHome) {
        if (confirmHome != null) {
            val pkg = homeSwitcher.currentWithFallback()?.pkg
            homeLabelForConfirm = homeSwitcher.launchers().firstOrNull { it.component.pkg == pkg }?.label
                ?: pkg
                ?: "your current home screen"
        }
    }

    SsScreen {
        val scrollInset = 8.dp
        Column(
            Modifier
                .fillMaxSize()
                .bleed(horizontal = scrollInset, vertical = scrollInset)
                .verticalScroll(rememberScrollState())
                .padding(scrollInset),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                SsButton("‹ Back", onClick = onBack, modifier = Modifier.focusRequester(backFocus))
                Spacer(Modifier.width(16.dp))
                Text("Turn off extras", style = SsType.Headline, color = SsColors.TextPrimary)
                if (busy) androidx.compose.foundation.layout.Box(Modifier.padding(start = 12.dp)) { SsSpinner() }
            }
            when (match) {
                ExtrasMatchResult.NoList -> {
                    Text("No list for this TV yet.", style = SsType.Body, color = SsColors.TextBody)
                    Text("Lists come from the CleanYourTV project.", style = SsType.Caption, color = SsColors.TextSecondary)
                }
                ExtrasMatchResult.Ambiguous -> {
                    Text("More than one list matches this TV.", style = SsType.Body, color = SsColors.DangerCardText)
                }
                is ExtrasMatchResult.Matched -> {
                    val meta = match.variants.getOrNull(variantIndex) ?: match.variants.first()
                    Text(shortHeader(meta), style = SsType.Title, color = SsColors.TextPrimary)
                    Text(
                        "List from CleanYourTV, ${upstreamCommitShort(index.upstreamCommit)}.",
                        style = SsType.Caption,
                        color = SsColors.TextSecondary,
                    )
                    if (match.variants.size > 1) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            match.variants.forEachIndexed { i, v ->
                                SsButton(
                                    v.variant,
                                    onClick = { variantIndex = i },
                                    selected = i == variantIndex,
                                )
                            }
                        }
                    }
                    rows.forEach { row ->
                        ExtrasRowUi(
                            row = row,
                            checked = checked[row.entry.pkg] == true,
                            onToggle = {
                                if (row.entry.home && row.selectable && checked[row.entry.pkg] != true) {
                                    confirmHome = row
                                } else {
                                    checked[row.entry.pkg] = !(checked[row.entry.pkg] == true)
                                }
                            },
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        SsButton(
                            "Turn off selected",
                            enabled = !busy && checked.any { it.value },
                            onClick = {
                                busy = true
                                scope.launch {
                                    val selected = checked.filterValues { it }.keys
                                    val metaNow = activeMeta ?: return@launch
                                    val entries = extras.entriesFor(metaNow)
                                    val homePkg = homeSwitcher.currentWithFallback()?.pkg
                                    val homeFlag = extras.homePkgHomeInList(metaNow, homePkg)
                                    val disabledSet = (commands.disabledPackages() as? ToolResult.Ok)?.value.orEmpty()
                                    val (outcome, stop) = extras.turnOffSelected(selected, entries, disabledSet, homeFlag)
                                    status = when (stop) {
                                        ExtrasRunStop.HelperDown -> "Helper not running. Start the helper, then try again."
                                        ExtrasRunStop.HelperOutdated -> "Restart the helper to use this."
                                        null -> formatRunOutcome(outcome)
                                    }
                                    refresh++
                                    busy = false
                                }
                            },
                        )
                        SsButton(
                            "Restore everything",
                            enabled = !busy && restoreEnabled,
                            onClick = {
                                busy = true
                                scope.launch {
                                    val (_, stop) = extras.restoreEverything()
                                    status = when (stop) {
                                        ExtrasRunStop.HelperDown -> "Helper not running. Start the helper, then try again."
                                        ExtrasRunStop.HelperOutdated -> "Restart the helper to use this."
                                        null -> "Restore finished."
                                    }
                                    refresh++
                                    busy = false
                                }
                            },
                        )
                    }
                }
            }
            confirmHome?.let { confirmRow ->
                val label = homeLabelForConfirm ?: "your current home screen"
                Text("Turn off a home screen app?", style = SsType.Title, color = SsColors.TextPrimary)
                Text(
                    "Your TV will keep using $label. You can turn it back on with Restore everything.",
                    style = SsType.Body,
                    color = SsColors.TextBody,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    SsButton(
                        "Turn off",
                        onClick = {
                            checked[confirmRow.entry.pkg] = true
                            confirmHome = null
                        },
                    )
                    SsButton("Cancel", onClick = { confirmHome = null })
                }
            }
            status?.let { Text(it, style = SsType.Body, color = SsColors.TextBody) }
        }
    }
}

private fun shortHeader(meta: ExtrasListMeta): String {
    val tv = meta.tv.substringBefore("(").trim().ifEmpty { meta.tv }
    return "$tv · ${meta.os}"
}

@Composable
private fun ExtrasRowUi(row: ExtrasRow, checked: Boolean, onToggle: () -> Unit) {
    val greyed = row.kind == ExtrasRowKind.NotInstalled
    val textColor = if (greyed) SsColors.TextSecondary else SsColors.TextBody
    val (pillLabel, pillBg, pillFg) = pillFor(row)
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (row.selectable) {
                SsButton(if (checked) "☑" else "☐", onClick = onToggle)
            } else {
                Spacer(Modifier.width(4.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(row.label, style = SsType.Body, color = textColor)
                Text(row.entry.pkg, style = SsType.Caption, color = SsColors.TextSecondary)
            }
            SsPill(pillLabel, pillBg, pillFg)
        }
        if (row.entry.note.isNotBlank()) {
            Text(row.entry.note, style = SsType.Caption, color = SsColors.TextSecondary)
        }
        row.caption?.let { Text(it, style = SsType.Caption, color = SsColors.TextSecondary) }
        row.failedLine?.let { Text("Failed: $it", style = SsType.Caption, color = SsColors.DangerCardText) }
    }
}

private fun pillFor(row: ExtrasRow): Triple<String, Color, Color> = when (row.kind) {
    ExtrasRowKind.On -> Triple("On", SsColors.OkPillBg, SsColors.OkPillText)
    ExtrasRowKind.Off -> Triple("Off", SsColors.Surface, SsColors.TextSecondary)
    ExtrasRowKind.NotInstalled -> Triple("Not installed", SsColors.Surface, SsColors.TextSecondary)
    ExtrasRowKind.Protected -> Triple("Protected", SsColors.WarnPillBg, SsColors.WarnPillText)
}