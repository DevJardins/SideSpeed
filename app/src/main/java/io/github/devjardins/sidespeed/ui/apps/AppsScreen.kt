package io.github.devjardins.sidespeed.ui.apps

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import io.github.devjardins.sidespeed.apps.AppEntry
import io.github.devjardins.sidespeed.compile.CompileMode
import io.github.devjardins.sidespeed.data.LastCompile
import kotlinx.coroutines.flow.Flow
import java.text.DateFormat
import java.util.Date

private enum class Tab(val label: String) { SIDELOADED("Sideloaded"), ALL("All"), MANUAL("Manual") }

@Composable
fun AppsScreen(
    vm: AppsViewModel,
    lastCompile: (String) -> Flow<LastCompile?>,
    onSelect: (AppEntry, CompileMode) -> Unit,
) {
    val state by vm.state.collectAsState()
    LaunchedEffect(Unit) { vm.load() }
    var tab by rememberSaveable { mutableStateOf(Tab.SIDELOADED) }
    var mode by rememberSaveable { mutableStateOf(CompileMode.SPEED) }
    // Give the D-pad a starting point: the active tab takes focus when the screen opens.
    val tabFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { tabFocus.requestFocus() } }

    Column(Modifier.fillMaxSize().padding(48.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Pick an app to compile", style = MaterialTheme.typography.displaySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Tab.entries.forEach { t ->
                Button(
                    onClick = { tab = t },
                    modifier = if (t == tab) Modifier.focusRequester(tabFocus) else Modifier,
                ) { Text(if (t == tab) "[ ${t.label} ]" else t.label) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Mode:", modifier = Modifier.padding(top = 12.dp))
            CompileMode.entries.forEach { m ->
                Button(onClick = { mode = m }) { Text(if (m == mode) "[ ${m.arg} ]" else m.arg) }
            }
        }
        when (tab) {
            Tab.MANUAL -> ManualEntry(vm) { onSelect(it, mode) }
            else -> {
                val apps = if (tab == Tab.SIDELOADED) state.apps.filter { it.isSideloaded } else state.apps
                when {
                    state.loading -> Text("Loading apps...")
                    apps.isEmpty() -> Text("No apps found.")
                    else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(apps, key = { it.packageName }) { app ->
                            AppRow(app, lastCompile(app.packageName)) { onSelect(app, mode) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AppRow(app: AppEntry, last: Flow<LastCompile?>, onClick: () -> Unit) {
    val lc by last.collectAsState(initial = null)
    Button(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column {
            Text(app.label, style = MaterialTheme.typography.titleMedium)
            Text(app.packageName, style = MaterialTheme.typography.bodySmall)
            lc?.let {
                val date = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(it.epochMs))
                Text("Compiled (${it.mode.arg}) · $date", style = MaterialTheme.typography.bodySmall)
            }
        }
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
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Package name, for example com.example.app")
        val color = MaterialTheme.colorScheme.onSurface
        BasicTextField(
            value = text,
            onValueChange = { text = it; error = null },
            singleLine = true,
            textStyle = TextStyle(color = color, fontSize = MaterialTheme.typography.titleLarge.fontSize),
            cursorBrush = SolidColor(color),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { submit() }),
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { focused = it.isFocused }
                .border(
                    BorderStroke(if (focused) 4.dp else 1.dp, if (focused) MaterialTheme.colorScheme.primary else color),
                    MaterialTheme.shapes.small,
                )
                .padding(16.dp),
        )
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(onClick = { submit() }) { Text("Continue") }
    }
}
