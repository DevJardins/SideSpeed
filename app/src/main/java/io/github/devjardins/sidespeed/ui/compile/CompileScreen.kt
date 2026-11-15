package io.github.devjardins.sidespeed.ui.compile

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import io.github.devjardins.sidespeed.apps.AppEntry
import io.github.devjardins.sidespeed.compile.CompileMode
import io.github.devjardins.sidespeed.compile.PackageName
import io.github.devjardins.sidespeed.status.StatusBadge
import kotlinx.coroutines.delay

const val RUNNING_NOTE = "A compile is running — please wait until it finishes."

const val COMPILE_NOTE = "This can take several minutes for large apps (about 3 min on a Fire TV Cube)"

fun badgeLabel(b: StatusBadge): String = when (b) {
    StatusBadge.SPEED_OK -> "speed ✓"
    StatusBadge.SPEED_PROFILE -> "speed-profile"
    StatusBadge.NEEDS_RECOMPILE -> "Needs recompile"
    StatusBadge.UNKNOWN -> "Unknown"
}

/** "Success" / "Failure", plus the message only when it adds something (avoids "Success: Success"). */
fun resultBanner(success: Boolean, message: String): String {
    val label = if (success) "Success" else "Failure"
    return if (message.isBlank() || message == label) label else "$label: $message"
}

fun formatElapsed(seconds: Long): String = "%02d:%02d".format(seconds / 60, seconds % 60)

@Composable
fun CompileScreen(
    vm: CompileViewModel,
    app: AppEntry,
    mode: CompileMode,
    onCheckHelper: () -> Unit,
) {
    val state by vm.state.collectAsState()
    val pkg = remember(app) { PackageName.parse(app.packageName) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val started = state.running || state.result != null
    val elapsed = ((now - state.startedAtMs) / 1000).coerceAtLeast(0)
    val listState = rememberLazyListState()
    val startFocus = remember { FocusRequester() }
    var logFocused by remember { mutableStateOf(false) }

    LaunchedEffect(app.packageName) { vm.reset(); runCatching { startFocus.requestFocus() } }
    LaunchedEffect(state.running) {
        if (state.running) {
            while (true) {
                now = System.currentTimeMillis()
                delay(500)
            }
        }
    }
    LaunchedEffect(state.log.size) {
        if (state.log.isNotEmpty()) listState.scrollToItem(state.log.size - 1)
    }

    Column(Modifier.fillMaxSize().padding(48.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("${app.label} (${app.packageName})", style = MaterialTheme.typography.headlineMedium)
        Text("Mode: ${mode.arg}")
        if (!started) Text(COMPILE_NOTE)
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Button(
                onClick = {
                    if (pkg != null) { vm.start(pkg, mode) }
                },
                enabled = !state.running && pkg != null,
                modifier = Modifier.focusRequester(startFocus),
            ) { Text(if (state.result == null) "Start compile" else "Compile again") }
            if (state.result?.message == "Connection lost") {
                Button(onClick = onCheckHelper) { Text("Check helper") }
            }
        }
        if (state.running) {
            Text(RUNNING_NOTE)
            Text("Compiling... ${formatElapsed(elapsed)}", style = MaterialTheme.typography.titleLarge)
        }
        state.result?.let { r ->
            val ok = r.success
            Text(
                resultBanner(r.success, r.message),
                style = MaterialTheme.typography.displaySmall,
                color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            )
            state.badge?.let { Text("Status: ${badgeLabel(it)}", style = MaterialTheme.typography.titleLarge) }
        }
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .onFocusChanged { logFocused = it.hasFocus }
                .border(
                    BorderStroke(
                        if (logFocused) 4.dp else 1.dp,
                        if (logFocused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    ),
                )
                .focusable()
                .padding(12.dp),
        ) {
            itemsIndexed(state.log) { _, line ->
                Text(line, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
