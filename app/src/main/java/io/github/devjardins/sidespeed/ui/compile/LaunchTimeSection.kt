package io.github.devjardins.sidespeed.ui.compile

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import io.github.devjardins.sidespeed.ui.theme.CardShape
import io.github.devjardins.sidespeed.ui.theme.SsButton
import io.github.devjardins.sidespeed.ui.theme.SsCard
import io.github.devjardins.sidespeed.ui.theme.SsColors
import io.github.devjardins.sidespeed.ui.theme.SsDimens
import io.github.devjardins.sidespeed.ui.theme.SsSpinner
import io.github.devjardins.sidespeed.ui.theme.SsType
import java.util.Locale
import kotlin.math.roundToInt

/** "1.9 s": one decimal, locale-independent so TVs never render "1,9 s". */
fun formatLaunchMs(ms: Long): String = "%.1f s".format(Locale.US, ms / 1000.0)

/**
 * "Launch: 1.9 s → 1.1 s (42% faster)", "Launch: 1.1 s" when there is no
 * before measurement, "(about the same)" when the difference is under 5%,
 * "(no faster)" when the app got 5%+ slower, and a "from an older version"
 * suffix when the app updated since the timing.
 */
fun formatLaunchResult(beforeMs: Long?, afterMs: Long, stale: Boolean): String {
    val base = if (beforeMs == null) {
        "Launch: ${formatLaunchMs(afterMs)}"
    } else {
        val pct = if (beforeMs > 0) (beforeMs - afterMs) * 100.0 / beforeMs else 0.0
        val verdict = when {
            formatLaunchMs(beforeMs) == formatLaunchMs(afterMs) -> "about the same"
            pct >= 5.0 -> "${pct.roundToInt()}% faster"
            pct <= -5.0 -> "no faster"
            else -> "about the same"
        }
        "Launch: ${formatLaunchMs(beforeMs)} → ${formatLaunchMs(afterMs)} ($verdict)"
    }
    return if (stale) "$base · from an older version" else base
}

/**
 * Launch-time before/after on the Compile screen. Option A (checkbox +
 * Start compile) appears only when there is no result for the installed
 * version; option B (Measure) always re-runs and replaces the result. While a
 * sequence is busy every action is disabled and the busy row takes D-pad focus.
 */
@Composable
fun LaunchTimeSection(
    launch: LaunchUiState,
    onCheckedChange: (Boolean) -> Unit,
    onMeasure: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val busyFocus = remember { FocusRequester() }
    val measureFocus = remember { FocusRequester() }
    var busyFocused by remember { mutableStateOf(false) }
    var wasBusy by remember { mutableStateOf(false) }
    LaunchedEffect(launch.busy) {
        if (launch.busy != null) {
            wasBusy = true
            // One frame later: the just-disabled buttons release D-pad focus first.
            kotlinx.coroutines.delay(200)
            runCatching { busyFocus.requestFocus() }
        } else if (wasBusy) {
            wasBusy = false
            runCatching { measureFocus.requestFocus() }
        }
    }
    SsCard(modifier.fillMaxWidth()) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Launch time", style = SsType.Title, color = SsColors.TextPrimary)
            val stored = launch.stored
            if (stored != null) {
                Text(
                    formatLaunchResult(stored.beforeMs, stored.afterMs, launch.stale),
                    style = SsType.Body,
                    color = SsColors.TextBody,
                )
            }
            if (!launch.launchable && stored == null) {
                Text(
                    "This app can't be opened from SideSpeed",
                    style = SsType.Body,
                    color = SsColors.TextSecondary,
                )
                return@Column
            }
            if (launch.launchable && launch.needsBefore) {
                SsButton(
                    if (launch.checked) "✓ Time the launch before and after" else "Time the launch before and after",
                    onClick = { onCheckedChange(!launch.checked) },
                    enabled = launch.busy == null,
                )
                Text(
                    "Opens the app on screen while timing.",
                    style = SsType.Caption,
                    color = SsColors.TextSecondary,
                )
            }
            if (launch.launchable) {
                SsButton(
                    "Measure now",
                    onClick = onMeasure,
                    enabled = launch.busy == null,
                    modifier = Modifier.focusRequester(measureFocus),
                )
                Text(
                    "Opens the app on screen twice. Takes a few minutes for large apps.",
                    style = SsType.Caption,
                    color = SsColors.TextSecondary,
                )
            }
            if (launch.busy != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth()
                        .onFocusChanged { busyFocused = it.hasFocus }
                        .background(if (busyFocused) SsColors.Raised else SsColors.Surface, CardShape)
                        .border(SsDimens.FocusRing, if (busyFocused) SsColors.Accent else SsColors.Surface, CardShape)
                        .focusRequester(busyFocus)
                        .focusable()
                        .padding(12.dp),
                ) {
                    SsSpinner(24.dp)
                    Text(launch.busy, style = SsType.Body, color = SsColors.TextBody)
                }
            }
            if (launch.error != null) {
                Text(launch.error, style = SsType.Body, color = SsColors.DangerCardText)
            }
        }
    }
}
