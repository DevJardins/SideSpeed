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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import io.github.devjardins.sidespeed.R
import io.github.devjardins.sidespeed.ui.text.UiText
import io.github.devjardins.sidespeed.ui.text.resolve
import io.github.devjardins.sidespeed.ui.text.uiText
import io.github.devjardins.sidespeed.ui.theme.CardShape
import io.github.devjardins.sidespeed.ui.theme.SsButton
import io.github.devjardins.sidespeed.ui.theme.SsCard
import io.github.devjardins.sidespeed.ui.theme.SsColors
import io.github.devjardins.sidespeed.ui.theme.SsDimens
import io.github.devjardins.sidespeed.ui.theme.SsSpinner
import io.github.devjardins.sidespeed.ui.theme.SsType
import java.util.Locale
import kotlin.math.roundToInt

private fun formatLaunchSecondsNumber(ms: Long): String = "%.1f".format(Locale.US, ms / 1000.0)

/** "1.9 s": one decimal in the current locale's digits. */
fun formatLaunchMs(ms: Long): UiText =
    uiText(R.string.compile_launch_seconds, UiText.Decimal(ms / 1000.0, 1))

/**
 * "Launch: 1.9 s → 1.1 s (42% faster)", "Launch: 1.1 s" when there is no
 * before measurement, "(about the same)" when the difference is under 5%,
 * "(no faster)" when the app got 5%+ slower, and a "from an older version"
 * suffix when the app updated since the timing.
 */
fun formatLaunchResult(beforeMs: Long?, afterMs: Long, stale: Boolean): UiText {
    val after = formatLaunchMs(afterMs)
    val base = if (beforeMs == null) {
        uiText(R.string.compile_launch_result_single, after)
    } else {
        val pct = if (beforeMs > 0) (beforeMs - afterMs) * 100.0 / beforeMs else 0.0
        val verdict = when {
            formatLaunchSecondsNumber(beforeMs) == formatLaunchSecondsNumber(afterMs) ->
                UiText.Res(R.string.compile_launch_verdict_same)
            pct >= 5.0 -> uiText(R.string.compile_launch_verdict_faster, pct.roundToInt())
            pct <= -5.0 -> UiText.Res(R.string.compile_launch_verdict_slower)
            else -> UiText.Res(R.string.compile_launch_verdict_same)
        }
        uiText(R.string.compile_launch_result_pair, formatLaunchMs(beforeMs), after, verdict)
    }
    return if (stale) uiText(R.string.compile_launch_result_stale, base) else base
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
            Text(stringResource(R.string.compile_launch_title), style = SsType.Title, color = SsColors.TextPrimary)
            val stored = launch.stored
            if (stored != null) {
                Text(
                    formatLaunchResult(stored.beforeMs, stored.afterMs, launch.stale).resolve(),
                    style = SsType.Body,
                    color = SsColors.TextBody,
                )
            }
            if (!launch.launchable && stored == null) {
                Text(
                    stringResource(R.string.compile_launch_not_launchable),
                    style = SsType.Body,
                    color = SsColors.TextSecondary,
                )
                return@Column
            }
            if (launch.launchable && launch.needsBefore) {
                val label = stringResource(R.string.compile_launch_time_before_after)
                SsButton(
                    if (launch.checked) stringResource(R.string.compile_selected, label) else label,
                    onClick = { onCheckedChange(!launch.checked) },
                    enabled = launch.busy == null,
                )
                Text(
                    stringResource(R.string.compile_launch_time_before_after_hint),
                    style = SsType.Caption,
                    color = SsColors.TextSecondary,
                )
            }
            if (launch.launchable) {
                SsButton(
                    stringResource(R.string.compile_launch_measure_now),
                    onClick = onMeasure,
                    enabled = launch.busy == null,
                    modifier = Modifier.focusRequester(measureFocus),
                )
                Text(
                    stringResource(R.string.compile_launch_measure_hint),
                    style = SsType.Caption,
                    color = SsColors.TextSecondary,
                )
            }
            val busyText = launch.busy
            if (busyText != null) {
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
                    Text(busyText.resolve(), style = SsType.Body, color = SsColors.TextBody)
                }
            }
            val errorText = launch.error
            if (errorText != null) {
                Text(errorText.resolve(), style = SsType.Body, color = SsColors.DangerCardText)
            }
        }
    }
}
