package io.github.devjardins.sidespeed.ui.apps

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import io.github.devjardins.sidespeed.maintenance.AutoOptimizeMode
import io.github.devjardins.sidespeed.maintenance.JobRecord
import io.github.devjardins.sidespeed.ui.theme.SsButton
import io.github.devjardins.sidespeed.ui.theme.SsColors
import io.github.devjardins.sidespeed.ui.theme.SsPill
import io.github.devjardins.sidespeed.ui.theme.SsRow
import io.github.devjardins.sidespeed.ui.theme.SsScreen
import io.github.devjardins.sidespeed.ui.theme.SsType
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.flow.Flow

/**
 * The Auto-optimize screen: the three modes with the current one's
 * description, and a History button opening the history view inside this
 * screen (Back from history returns to the modes).
 */
@Composable
fun AutoOptimizeScreen(
    mode: Flow<AutoOptimizeMode>,
    onMode: (AutoOptimizeMode) -> Unit,
    history: Flow<List<JobRecord>>,
    onBack: () -> Unit,
) {
    var showHistory by remember { mutableStateOf(false) }
    val current by mode.collectAsState(initial = null)
    // Give the D-pad a starting point: the current mode takes focus when the screen opens,
    // and the History button takes it back when you return from History.
    val modeFocus = remember { FocusRequester() }
    val historyFocus = remember { FocusRequester() }
    var returnToHistory by remember { mutableStateOf(false) }
    LaunchedEffect(current, showHistory) {
        if (showHistory || current == null) return@LaunchedEffect
        if (returnToHistory) {
            returnToHistory = false
            runCatching { historyFocus.requestFocus() }
        } else {
            runCatching { modeFocus.requestFocus() }
        }
    }
    val historyBackFocus = remember { FocusRequester() }
    LaunchedEffect(showHistory) {
        if (showHistory) runCatching { historyBackFocus.requestFocus() }
    }

    if (showHistory) {
        BackHandler { showHistory = false }
        SsScreen {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                // Back sits beside the title, as on About, so a long history can't push it off screen.
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    SsButton("‹ Back", onClick = { showHistory = false }, modifier = Modifier.focusRequester(historyBackFocus))
                    Spacer(Modifier.width(16.dp))
                    Text("Auto-optimize history", style = SsType.Headline, color = SsColors.TextPrimary)
                }
                HistoryList(history)
            }
        }
        return
    }

    BackHandler(onBack = onBack)
    SsScreen {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                SsButton("‹ Back", onClick = onBack)
                Spacer(Modifier.width(16.dp))
                Text("Auto-optimize", style = SsType.Headline, color = SsColors.TextPrimary)
            }
            current?.let {
                AutoOptimizeMode.entries.forEach { m ->
                    SsButton(
                        (if (m == it) "✓ " else "") + autoOptimizeOption(m).title,
                        onClick = { onMode(m) },
                        selected = m == it,
                        modifier = if (m == it) Modifier.focusRequester(modeFocus) else Modifier,
                    )
                }
                Text(
                    autoOptimizeOption(it).description,
                    style = SsType.Caption,
                    color = SsColors.TextSecondary,
                )
            }
            SsButton(
                "History",
                onClick = { returnToHistory = true; showHistory = true },
                modifier = Modifier.focusRequester(historyFocus),
            )
        }
    }
}

@Composable
private fun HistoryList(history: Flow<List<JobRecord>>) {
    val records by history.collectAsState(initial = emptyList())
    if (records.isEmpty()) {
        Text("Nothing has been optimized automatically yet.", style = SsType.Body, color = SsColors.TextSecondary)
        return
    }
    val format = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(vertical = 4.dp, horizontal = 4.dp),
    ) {
        items(records) { r ->
            // Focusable so the D-pad can scroll the list.
            SsRow(onClick = {}, modifier = Modifier.fillMaxWidth()) {
                Row(
                    Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(r.label, style = SsType.Body, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(format.format(Date(r.epochMs)), style = SsType.Caption, color = SsColors.TextSecondary)
                    }
                    if (r.success) SsPill("Optimized", SsColors.OkPillBg, SsColors.OkPillText)
                    else SsPill("Failed: ${r.message}", SsColors.WarnPillBg, SsColors.WarnPillText)
                }
            }
        }
    }
}
