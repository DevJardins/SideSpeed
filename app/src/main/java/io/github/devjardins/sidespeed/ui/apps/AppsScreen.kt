package io.github.devjardins.sidespeed.ui.apps

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import io.github.devjardins.sidespeed.apps.AppEntry
import io.github.devjardins.sidespeed.apps.IconSource
import io.github.devjardins.sidespeed.compile.CompileMode
import io.github.devjardins.sidespeed.data.LastCompile
import io.github.devjardins.sidespeed.maintenance.AutoOptimizeMode
import io.github.devjardins.sidespeed.maintenance.MaintenanceState
import io.github.devjardins.sidespeed.ui.theme.SsButton
import io.github.devjardins.sidespeed.ui.theme.SsColors
import io.github.devjardins.sidespeed.ui.theme.SsDimens
import io.github.devjardins.sidespeed.ui.theme.SsPill
import io.github.devjardins.sidespeed.ui.theme.SsRow
import io.github.devjardins.sidespeed.ui.theme.SsScreen
import io.github.devjardins.sidespeed.ui.theme.SsType
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import java.text.DateFormat
import java.util.Date

/** One width for every keep box, so the app rows line up whatever the box shows. */
private val KeepBoxWidth = 88.dp

@Composable
fun AppsScreen(
    vm: AppsViewModel,
    lastCompilesFlow: Flow<Map<String, LastCompile>>,
    icon: IconSource,
    maintenance: StateFlow<MaintenanceState>,
    autoOptimizeMode: Flow<AutoOptimizeMode>,
    maintained: Flow<Set<String>>,
    onToggleKeep: (String, Boolean) -> Unit,
    onSelect: (AppEntry, CompileMode) -> Unit,
    onBack: () -> Unit,
) {
    val state by vm.state.collectAsState()
    // One DataStore collection for every row, instead of one Flow per row.
    val lastCompiles by lastCompilesFlow.collectAsState(initial = emptyMap())
    val maintenanceState by maintenance.collectAsState()
    val optimizeMode: AutoOptimizeMode? by autoOptimizeMode.collectAsState(initial = null)
    val ticked by maintained.collectAsState(initial = emptySet())
    LaunchedEffect(Unit) { vm.load() }
    // Save the filter by name and fall back to SIDELOADED for a name this build no
    // longer has (e.g. a state bundle written by 0.6 with "HISTORY"), so a stale
    // restore can never crash on a removed entry.
    var filterName by rememberSaveable { mutableStateOf(AppsFilter.SIDELOADED.name) }
    val filter = filterFromSaved(filterName)
    // A short note under the filter row, e.g. when a locked box is pressed.
    var lockedNote by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(lockedNote) {
        if (lockedNote != null) {
            delay(4000)
            lockedNote = null
        }
    }
    // Give the D-pad a starting point: the active filter takes focus when the screen opens.
    val filterFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { filterFocus.requestFocus() } }

    BackHandler(onBack = onBack)
    SsScreen {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                SsButton("‹ Back", onClick = onBack)
                Spacer(Modifier.width(16.dp))
                Text(
                    "My Apps",
                    style = SsType.Headline,
                    color = SsColors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AppsFilter.entries.forEach { f ->
                    SsButton(
                        onClick = { filterName = f.name },
                        selected = f == filter,
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                        modifier = if (f == filter) Modifier.focusRequester(filterFocus) else Modifier,
                    ) {
                        Text(f.label, style = SsType.Body)
                    }
                }
            }
            lockedNote?.let {
                Text(it, style = SsType.Caption, color = SsColors.TextSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            val apps = remember(state.apps, filter) {
                if (filter == AppsFilter.SIDELOADED) state.apps.filter { it.isSideloaded } else state.apps
            }
            when {
                state.loading -> Text("Loading apps…", style = SsType.Body, color = SsColors.TextSecondary)
                apps.isEmpty() -> Text("No apps found.", style = SsType.Body, color = SsColors.TextSecondary)
                else -> LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(vertical = 4.dp, horizontal = 4.dp),
                ) {
                    items(apps, key = { it.packageName }) { app ->
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            AppRow(
                                app,
                                lastCompiles[app.packageName],
                                maintenancePill(app.packageName, maintenanceState)
                                    ?: pillFor(state.statusLoading, state.statuses[app.packageName]),
                                icon,
                                Modifier.weight(1f),
                                onClick = { onSelect(app, initialCompileMode(lastCompiles[app.packageName])) },
                            )
                            // While the mode is still loading there is nothing to compare against: no box.
                            val current = optimizeMode
                            if (current != null) {
                                when (val box = boxFor(app, current, ticked)) {
                                    KeepBox.OFF, KeepBox.ON -> KeepBoxButton(if (box == KeepBox.ON) "☑" else "☐") {
                                        onToggleKeep(app.packageName, box == KeepBox.OFF)
                                    }
                                    KeepBox.LOCKED -> KeepBoxButton("☑🔒") {
                                        lockedNote = lockedMessage(current)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AppIcon(pkg: String, icon: IconSource, size: androidx.compose.ui.unit.Dp = SsDimens.Icon) {
    // Seeded from the cache so a known icon shows on the first frame, with no placeholder flash.
    val bitmap by produceState(icon.cached(pkg), pkg) { value = icon.cached(pkg) ?: icon.load(pkg) }
    val b = bitmap
    if (b != null) {
        Image(b, contentDescription = null, modifier = Modifier.size(size))
    } else {
        Box(Modifier.size(size).background(SsColors.Button, RoundedCornerShape(8.dp)))
    }
}

/** A keep box with its glyph centered, at the fixed row width. */
@Composable
private fun KeepBoxButton(label: String, onClick: () -> Unit) {
    SsButton(
        onClick = onClick,
        modifier = Modifier.width(KeepBoxWidth),
        contentPadding = PaddingValues(vertical = 10.dp),
    ) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text(label, style = SsType.Body)
        }
    }
}

@Composable
private fun AppRow(app: AppEntry, last: LastCompile?, pill: Pill?, icon: IconSource, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val dateFormat = remember { DateFormat.getDateInstance(DateFormat.MEDIUM) }
    val secondary = remember(last, app.packageName) {
        last?.let { "compiled " + dateFormat.format(Date(it.epochMs)) } ?: app.packageName
    }
    SsRow(onClick = onClick, modifier = modifier) {
        Row(
            Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            AppIcon(app.packageName, icon)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(app.label, style = SsType.Body, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(secondary, style = SsType.Caption, color = SsColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            pill?.let { StatusPill(it) }
        }
    }
}

@Composable
fun StatusPill(pill: Pill) {
    when (pill.tone) {
        PillTone.OK -> SsPill(pill.label, SsColors.OkPillBg, SsColors.OkPillText)
        PillTone.WARN -> SsPill(pill.label, SsColors.WarnPillBg, SsColors.WarnPillText)
        PillTone.NEUTRAL -> SsPill(pill.label, SsColors.Button, SsColors.TextSecondary)
    }
}
