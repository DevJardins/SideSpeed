package io.github.devjardins.sidespeed.ui.apps

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import io.github.devjardins.sidespeed.apps.AppEntry
import io.github.devjardins.sidespeed.apps.IconSource
import io.github.devjardins.sidespeed.compile.CompileMode
import io.github.devjardins.sidespeed.data.LastCompile
import io.github.devjardins.sidespeed.maintenance.AutoOptimizeMode
import io.github.devjardins.sidespeed.maintenance.JobRecord
import io.github.devjardins.sidespeed.maintenance.MaintenanceState
import io.github.devjardins.sidespeed.ui.theme.SsButton
import io.github.devjardins.sidespeed.ui.theme.SsColors
import io.github.devjardins.sidespeed.ui.theme.SsDimens
import io.github.devjardins.sidespeed.ui.theme.SsPill
import io.github.devjardins.sidespeed.ui.theme.SsRow
import io.github.devjardins.sidespeed.ui.theme.SsScreen
import io.github.devjardins.sidespeed.ui.theme.SsType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import java.text.DateFormat
import java.util.Date

private enum class Tab(val label: String) { SIDELOADED("Sideloaded"), ALL("All apps"), MANUAL("Type a package"), HISTORY("Auto-optimize") }

@Composable
fun AppsScreen(
    vm: AppsViewModel,
    lastCompile: (String) -> Flow<LastCompile?>,
    icon: IconSource,
    maintenance: StateFlow<MaintenanceState>,
    history: Flow<List<JobRecord>>,
    autoOptimizeMode: Flow<AutoOptimizeMode>,
    onAutoOptimizeMode: (AutoOptimizeMode) -> Unit,
    onSelect: (AppEntry, CompileMode) -> Unit,
    tvAddress: String? = null,
) {
    val state by vm.state.collectAsState()
    val maintenanceState by maintenance.collectAsState()
    val optimizeMode: AutoOptimizeMode? by autoOptimizeMode.collectAsState(initial = null)
    LaunchedEffect(Unit) { vm.load() }
    var tab by rememberSaveable { mutableStateOf(Tab.SIDELOADED) }
    var mode by rememberSaveable { mutableStateOf(CompileMode.SPEED) }
    // Give the D-pad a starting point: the active tab takes focus when the screen opens.
    val tabFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { tabFocus.requestFocus() } }

    SsScreen {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Pick an app to compile", style = SsType.Headline, color = SsColors.TextPrimary)
                tvAddress?.let { Spacer(Modifier.width(16.dp)); Text(it, style = SsType.Caption, color = SsColors.TextSecondary) }
                Spacer(Modifier.weight(1f))
                SsPill("● Helper running", SsColors.OkPillBg, SsColors.OkPillText)
            }
            maintenanceLine(maintenanceState)?.let {
                Text(it, style = SsType.Caption, color = SsColors.TextSecondary)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Tab.entries.forEach { t ->
                    SsButton(
                        t.label,
                        onClick = { tab = t },
                        selected = t == tab,
                        modifier = if (t == tab) Modifier.focusRequester(tabFocus) else Modifier,
                    )
                }
                Spacer(Modifier.width(24.dp))
                Text("Mode", style = SsType.Caption, color = SsColors.TextSecondary)
                CompileMode.entries.forEach { m ->
                    SsButton(
                        onClick = { mode = m },
                        selected = m == mode,
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                    ) { Text(if (m == mode) "✓ ${m.arg}" else m.arg, style = SsType.Caption) }
                }
            }
            if (tab == Tab.HISTORY) {
                val current = optimizeMode
                if (current != null) {
                    Text("Keep these apps optimized automatically", style = SsType.Caption, color = SsColors.TextSecondary)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        AutoOptimizeMode.entries.reversed().forEach { m ->
                            val option = autoOptimizeOption(m)
                            SsButton(
                                (if (m == current) "✓ " else "") + option.title,
                                onClick = { onAutoOptimizeMode(m) },
                                selected = m == current,
                            )
                        }
                    }
                    Text(autoOptimizeOption(current).description, style = SsType.Caption, color = SsColors.TextSecondary)
                }
            }
            when (tab) {
                Tab.MANUAL -> ManualEntry(vm) { onSelect(it, mode) }
                Tab.HISTORY -> HistoryList(history)
                else -> {
                    val apps = if (tab == Tab.SIDELOADED) state.apps.filter { it.isSideloaded } else state.apps
                    when {
                        state.loading -> Text("Loading apps…", style = SsType.Body, color = SsColors.TextSecondary)
                        apps.isEmpty() -> Text("No apps found.", style = SsType.Body, color = SsColors.TextSecondary)
                        else -> LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            contentPadding = PaddingValues(vertical = 4.dp, horizontal = 4.dp),
                        ) {
                            items(apps, key = { it.packageName }) { app ->
                                AppRow(
                                    app,
                                    lastCompile(app.packageName),
                                    maintenancePill(app.packageName, maintenanceState)
                                        ?: pillFor(state.statusLoading, state.statuses[app.packageName]),
                                    icon,
                                ) { onSelect(app, mode) }
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

@Composable
private fun AppRow(app: AppEntry, last: Flow<LastCompile?>, pill: Pill?, icon: IconSource, onClick: () -> Unit) {
    val lc by last.collectAsState(initial = null)
    SsRow(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            AppIcon(app.packageName, icon)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(app.label, style = SsType.Body, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val secondary = lc?.let {
                    "compiled " + DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(it.epochMs))
                } ?: app.packageName
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

@Composable
private fun ManualEntry(vm: AppsViewModel, onValid: (AppEntry) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var focused by remember { mutableStateOf(false) }
    val submit = {
        when (val r = vm.manualEntry(text)) {
            is ManualResult.Valid -> { error = null; onValid(r.app) }
            ManualResult.NotInstalled -> error = "Not installed"
            ManualResult.Invalid -> error = "Not a valid package name"
        }
    }
    val shape = RoundedCornerShape(10.dp)
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Package name, for example com.example.app", style = SsType.Body, color = SsColors.TextBody)
        BasicTextField(
            value = text,
            onValueChange = { text = it; error = null },
            singleLine = true,
            textStyle = SsType.Title.copy(color = SsColors.TextPrimary),
            cursorBrush = SolidColor(SsColors.Accent),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { submit() }),
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { focused = it.isFocused }
                .background(if (focused) SsColors.Raised else SsColors.Surface, shape)
                .border(
                    BorderStroke(SsDimens.FocusRing, if (focused) SsColors.Accent else SsColors.Button),
                    shape,
                )
                .padding(16.dp),
        )
        error?.let { Text(it, style = SsType.Body, color = SsColors.DangerCardText) }
        SsButton("Continue", onClick = { submit() })
    }
}

@Composable
private fun HistoryList(history: Flow<List<JobRecord>>) {
    val records by history.collectAsState(initial = emptyList())
    if (records.isEmpty()) {
        Text("Nothing has been optimized automatically yet.", style = SsType.Body, color = SsColors.TextSecondary)
        return
    }
    val format = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
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
