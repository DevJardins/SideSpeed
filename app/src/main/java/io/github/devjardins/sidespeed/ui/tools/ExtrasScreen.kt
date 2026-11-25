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
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
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
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import io.github.devjardins.sidespeed.R
import io.github.devjardins.sidespeed.tools.Extras
import io.github.devjardins.sidespeed.tools.ExtrasIndex
import io.github.devjardins.sidespeed.tools.ExtrasListMeta
import io.github.devjardins.sidespeed.tools.ExtrasMatchResult
import io.github.devjardins.sidespeed.tools.ExtrasRow
import io.github.devjardins.sidespeed.tools.ExtrasRowKind
import io.github.devjardins.sidespeed.tools.ExtrasRunStop
import io.github.devjardins.sidespeed.tools.HomeSwitcher
import io.github.devjardins.sidespeed.tools.formatRestoreOutcome
import io.github.devjardins.sidespeed.tools.formatRunOutcome
import io.github.devjardins.sidespeed.tools.formatTurnOnOutcome
import io.github.devjardins.sidespeed.tools.upstreamCommitShort
import io.github.devjardins.sidespeed.ui.text.UiText
import io.github.devjardins.sidespeed.ui.text.resolve
import io.github.devjardins.sidespeed.ui.text.uiText
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
    homeSwitcher: HomeSwitcher,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val scope = rememberCoroutineScope()
    val backFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { backFocus.requestFocus() } }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<UiText?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    var variantIndex by remember { mutableIntStateOf(0) }
    var rows by remember { mutableStateOf<List<ExtrasRow>>(emptyList()) }
    var activeMeta by remember { mutableStateOf<ExtrasListMeta?>(null) }
    val checked = remember { mutableStateMapOf<String, Boolean>() }
    var confirmHome by remember { mutableStateOf<ExtrasRow?>(null) }
    var confirmHomeOn by remember { mutableStateOf<ExtrasRow?>(null) }
    var restoreEnabled by remember { mutableStateOf(false) }
    var homeLabelForConfirm by remember { mutableStateOf<UiText?>(null) }
    var rowFailures by remember { mutableStateOf<Map<String, UiText>>(emptyMap()) }
    var helperBlock by remember { mutableStateOf<UiText?>(null) }
    var protectionFailed by remember { mutableStateOf(false) }

    val actionsBringIntoView = remember { BringIntoViewRequester() }

    val match = remember(refresh) {
        extras.match(Build.MANUFACTURER, Build.MODEL, Build.DEVICE)
    }
    var dismissedIds by remember { mutableStateOf(emptySet<String>()) }
    var acceptedIds by remember { mutableStateOf(emptySet<String>()) }
    // Until the saved answers load, a suggestion shows nothing, so a remembered
    // "Not my TV" or "Use this list" never flashes the question first.
    var suggestionsLoaded by remember { mutableStateOf(false) }
    val useListFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        dismissedIds = extras.dismissedSuggestions()
        acceptedIds = extras.acceptedSuggestions()
        suggestionsLoaded = true
    }
    // A dismissed suggestion reads as NoList; an accepted one opens as its list.
    val effectiveMatch = when (val m = match) {
        is ExtrasMatchResult.Suggested -> when {
            m.folderId in dismissedIds -> ExtrasMatchResult.NoList
            m.folderId in acceptedIds -> ExtrasMatchResult.Matched(m.folderId, m.variants)
            else -> m
        }
        else -> m
    }

    LaunchedEffect(effectiveMatch, variantIndex, refresh, rowFailures) {
        when (val m = effectiveMatch) {
            is ExtrasMatchResult.Matched -> {
                val meta = m.variants.getOrNull(variantIndex) ?: m.variants.first()
                activeMeta = meta
                val result = extras.refresh(meta, rowFailures)
                helperBlock = result.helperMessage
                protectionFailed = result.protectionCheckFailed
                if (result.helperMessage != null) {
                    rows = emptyList()
                    restoreEnabled = false
                } else {
                    rows = result.rows
                    restoreEnabled = result.recordedCount > 0
                    rows.filter { it.selectable }.forEach { row ->
                        if (row.entry.pkg !in checked) checked[row.entry.pkg] = row.checked
                    }
                }
            }
            else -> {
                activeMeta = null
                rows = emptyList()
                restoreEnabled = false
                helperBlock = null
                protectionFailed = false
            }
        }
    }

    LaunchedEffect(status) {
        if (status != null) {
            withFrameNanos { }
            runCatching { actionsBringIntoView.bringIntoView() }
        }
    }

    LaunchedEffect(confirmHome) {
        if (confirmHome != null) {
            val pkg = homeSwitcher.currentWithFallback()?.pkg
            val label = homeSwitcher.launchers().firstOrNull { it.component.pkg == pkg }?.label
            homeLabelForConfirm = when {
                label != null -> UiText.Raw(label)
                pkg != null -> UiText.Ltr(pkg)
                else -> UiText.Res(R.string.extras_current_home_fallback)
            }
        }
    }

    val actionsBlocked = busy || helperBlock != null || protectionFailed

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
                SsButton(stringResource(R.string.common_back), onClick = onBack, modifier = Modifier.focusRequester(backFocus))
                Spacer(Modifier.width(16.dp))
                Text(stringResource(R.string.extras_title), style = SsType.Headline, color = SsColors.TextPrimary)
                if (busy) androidx.compose.foundation.layout.Box(Modifier.padding(start = 12.dp)) { SsSpinner() }
            }
            when (val m = effectiveMatch) {
                ExtrasMatchResult.NoList -> {
                    Text(stringResource(R.string.extras_no_list), style = SsType.Body, color = SsColors.TextBody)
                    Text(stringResource(R.string.extras_source_note), style = SsType.Caption, color = SsColors.TextSecondary)
                }
                ExtrasMatchResult.Ambiguous -> {
                    Text(stringResource(R.string.extras_ambiguous), style = SsType.Body, color = SsColors.DangerCardText)
                }
                is ExtrasMatchResult.Suggested -> if (suggestionsLoaded) {
                    LaunchedEffect(Unit) { runCatching { useListFocus.requestFocus() } }
                    Text(
                        stringResource(R.string.extras_suggested_prompt, m.tvHeader),
                        style = SsType.Body,
                        color = SsColors.TextBody,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        SsButton(
                            stringResource(R.string.extras_use_this_list),
                            modifier = Modifier.focusRequester(useListFocus),
                            onClick = {
                                scope.launch {
                                    extras.acceptSuggestion(m.folderId)
                                    acceptedIds = acceptedIds + m.folderId
                                }
                            },
                        )
                        SsButton(
                            stringResource(R.string.extras_not_my_tv),
                            onClick = {
                                scope.launch {
                                    extras.dismissSuggestion(m.folderId)
                                    dismissedIds = dismissedIds + m.folderId
                                }
                            },
                        )
                    }
                }
                is ExtrasMatchResult.Matched -> {
                    val meta = m.variants.getOrNull(variantIndex) ?: m.variants.first()
                    Text(shortHeader(meta), style = SsType.Title, color = SsColors.TextPrimary)
                    Text(
                        uiText(R.string.extras_list_credit, UiText.Ltr(upstreamCommitShort(index.upstreamCommit))).resolve(),
                        style = SsType.Caption,
                        color = SsColors.TextSecondary,
                    )
                    if (m.variants.size > 1) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            m.variants.forEachIndexed { i, v ->
                                SsButton(
                                    v.variant,
                                    onClick = { variantIndex = i },
                                    selected = i == variantIndex,
                                )
                            }
                        }
                    }
                    helperBlock?.let { msg ->
                        Text(msg.resolve(), style = SsType.Body, color = SsColors.DangerCardText)
                    }
                    if (protectionFailed) {
                        Text(Extras.PROTECTION_CHECK_FAILED_MSG.resolve(), style = SsType.Body, color = SsColors.DangerCardText)
                    }
                    rows.forEach { row ->
                        ExtrasRowUi(
                            row = row,
                            checked = checked[row.entry.pkg] == true,
                            onToggle = {
                                if (row.entry.home && row.selectable && checked[row.entry.pkg] != true) {
                                    if (row.kind == ExtrasRowKind.On) confirmHome = row else confirmHomeOn = row
                                } else {
                                    checked[row.entry.pkg] = !(checked[row.entry.pkg] == true)
                                }
                            },
                        )
                    }
                    Column(
                        Modifier.bringIntoViewRequester(actionsBringIntoView),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        status?.let { Text(it.resolve(), style = SsType.Body, color = SsColors.TextBody) }
                        val rowsByPkg = rows.associateBy { it.entry.pkg }
                        val tickedOn = checked.filterValues { it }.keys.filter { rowsByPkg[it]?.kind == ExtrasRowKind.On }.toSet()
                        val tickedOff = checked.filterValues { it }.keys.filter { rowsByPkg[it]?.kind == ExtrasRowKind.Off }.toSet()
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            SsButton(
                                stringResource(R.string.extras_turn_off_selected),
                                enabled = !actionsBlocked && tickedOn.isNotEmpty(),
                                onClick = {
                                    busy = true
                                    rowFailures = emptyMap()
                                    scope.launch {
                                        val metaNow = activeMeta ?: return@launch
                                        val entries = extras.entriesFor(metaNow)
                                        val homePkg = homeSwitcher.currentWithFallback()?.pkg
                                        val homeFlag = extras.homePkgHomeInList(metaNow, homePkg)
                                        val facts = extras.loadProtectionFacts()
                                        if (facts == null) {
                                            status = Extras.PROTECTION_CHECK_FAILED_MSG
                                            checked.clear()
                                            refresh++
                                            busy = false
                                            return@launch
                                        }
                                        val disabledSet = (extras.readDisabledPackages() as? io.github.devjardins.sidespeed.tools.ToolResult.Ok)?.value
                                        if (disabledSet == null) {
                                            status = Extras.HELPER_DOWN_MSG
                                            checked.clear()
                                            refresh++
                                            busy = false
                                            return@launch
                                        }
                                        val (outcome, stop) = extras.turnOffSelected(tickedOn, entries, disabledSet, homeFlag, facts)
                                        rowFailures = outcome.failedByPkg
                                        status = when (stop) {
                                            ExtrasRunStop.HelperDown -> Extras.HELPER_DOWN_MSG
                                            ExtrasRunStop.HelperOutdated -> Extras.HELPER_OUTDATED_MSG
                                            null -> formatRunOutcome(outcome)
                                        }
                                        checked.clear()
                                        refresh++
                                        busy = false
                                    }
                                },
                            )
                            SsButton(
                                stringResource(R.string.extras_turn_on_selected),
                                enabled = !actionsBlocked && tickedOff.isNotEmpty(),
                                onClick = {
                                    busy = true
                                    rowFailures = emptyMap()
                                    scope.launch {
                                        val metaNow = activeMeta ?: return@launch
                                        val entries = extras.entriesFor(metaNow)
                                        val facts = extras.loadProtectionFacts()
                                        if (facts == null) {
                                            status = Extras.PROTECTION_CHECK_FAILED_MSG
                                            checked.clear()
                                            refresh++
                                            busy = false
                                            return@launch
                                        }
                                        val disabledSet = (extras.readDisabledPackages() as? io.github.devjardins.sidespeed.tools.ToolResult.Ok)?.value
                                        if (disabledSet == null) {
                                            status = Extras.HELPER_DOWN_MSG
                                            checked.clear()
                                            refresh++
                                            busy = false
                                            return@launch
                                        }
                                        val (outcome, stop) = extras.turnOnSelected(tickedOff, entries, disabledSet, facts)
                                        rowFailures = outcome.failedByPkg
                                        status = when (stop) {
                                            ExtrasRunStop.HelperDown -> Extras.HELPER_DOWN_MSG
                                            ExtrasRunStop.HelperOutdated -> Extras.HELPER_OUTDATED_MSG
                                            null -> formatTurnOnOutcome(outcome)
                                        }
                                        checked.clear()
                                        refresh++
                                        busy = false
                                    }
                                },
                            )
                            SsButton(
                                stringResource(R.string.extras_restore_everything),
                                enabled = !actionsBlocked && restoreEnabled,
                                onClick = {
                                    busy = true
                                    rowFailures = emptyMap()
                                    scope.launch {
                                        val (outcome, stop) = extras.restoreEverything()
                                        rowFailures = outcome.failedByPkg
                                        status = when (stop) {
                                            ExtrasRunStop.HelperDown -> Extras.HELPER_DOWN_MSG
                                            ExtrasRunStop.HelperOutdated -> Extras.HELPER_OUTDATED_MSG
                                            null -> formatRestoreOutcome(outcome)
                                        }
                                        checked.clear()
                                        refresh++
                                        busy = false
                                    }
                                },
                            )
                        }
                    }
                }
            }
            confirmHome?.let { confirmRow ->
                val label = homeLabelForConfirm ?: UiText.Res(R.string.extras_current_home_fallback)
                Text(stringResource(R.string.extras_confirm_home_off_title), style = SsType.Title, color = SsColors.TextPrimary)
                Text(
                    uiText(R.string.extras_confirm_home_off_body, label).resolve(),
                    style = SsType.Body,
                    color = SsColors.TextBody,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    SsButton(
                        stringResource(R.string.extras_turn_off),
                        onClick = {
                            checked[confirmRow.entry.pkg] = true
                            confirmHome = null
                        },
                    )
                    SsButton(stringResource(R.string.tools_cancel), onClick = { confirmHome = null })
                }
            }
            confirmHomeOn?.let { confirmRow ->
                val appArg = if (confirmRow.label == confirmRow.entry.pkg) UiText.Ltr(confirmRow.label) else confirmRow.label
                Text(stringResource(R.string.extras_confirm_home_on_title), style = SsType.Title, color = SsColors.TextPrimary)
                Text(
                    uiText(R.string.extras_confirm_home_on_body, appArg).resolve(),
                    style = SsType.Body,
                    color = SsColors.TextBody,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    SsButton(
                        stringResource(R.string.extras_turn_on),
                        onClick = {
                            checked[confirmRow.entry.pkg] = true
                            confirmHomeOn = null
                        },
                    )
                    SsButton(stringResource(R.string.tools_cancel), onClick = { confirmHomeOn = null })
                }
            }
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
    val (pillLabelRes, pillBg, pillFg) = pillFor(row)
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
            SsPill(stringResource(pillLabelRes), pillBg, pillFg)
        }
        if (row.entry.note.isNotBlank()) {
            Text(row.entry.note, style = SsType.Caption, color = SsColors.TextSecondary)
        }
        row.caption?.let { Text(it.resolve(), style = SsType.Caption, color = SsColors.TextSecondary) }
        row.failedLine?.let { Text(uiText(R.string.extras_row_failed, it).resolve(), style = SsType.Caption, color = SsColors.DangerCardText) }
    }
}

private fun pillFor(row: ExtrasRow): Triple<Int, Color, Color> = when (row.kind) {
    ExtrasRowKind.On -> Triple(R.string.extras_pill_on, SsColors.OkPillBg, SsColors.OkPillText)
    ExtrasRowKind.Off -> Triple(R.string.extras_pill_off, SsColors.Surface, SsColors.TextSecondary)
    ExtrasRowKind.NotInstalled -> Triple(R.string.extras_pill_not_installed, SsColors.Surface, SsColors.TextSecondary)
    ExtrasRowKind.Protected -> Triple(R.string.extras_pill_protected, SsColors.WarnPillBg, SsColors.WarnPillText)
}