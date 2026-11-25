package io.github.devjardins.sidespeed.ui.tools

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import io.github.devjardins.sidespeed.ui.theme.bleed
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import io.github.devjardins.sidespeed.R
import io.github.devjardins.sidespeed.ui.theme.SsButton
import io.github.devjardins.sidespeed.ui.theme.SsCard
import io.github.devjardins.sidespeed.ui.theme.SsColors
import io.github.devjardins.sidespeed.ui.theme.SsScreen
import io.github.devjardins.sidespeed.ui.theme.SsType

/**
 * The Tools screen shell: a scrollable column with a title and four card slots in fixed order —
 * [health] (Box health), [space] (Free up space), [animations] (Animations), [extras] (Turn off extras), [home] (Home screen).
 *
 * [initialFocus] is the control to focus when the screen opens, such as the button that opened
 * a screen you are coming back from; with none, the Back button takes focus.
 */
@Composable
fun ToolsScreen(
    health: @Composable () -> Unit,
    space: @Composable () -> Unit,
    animations: @Composable () -> Unit,
    extras: @Composable () -> Unit,
    home: @Composable () -> Unit,
    onBack: () -> Unit,
    initialFocus: FocusRequester? = null,
) {
    BackHandler(onBack = onBack)
    // Give the D-pad a starting point: the Back button, or [initialFocus] when coming back.
    val backFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { (initialFocus ?: backFocus).requestFocus() } }
    SsScreen {
        // The scroll clips to the column bounds, which would cut the first and last
        // focusables' 2dp focus ring and focus scale: bleed the column out
        // symmetrically and pad the scroll content by the same amount, so resting
        // edges stay aligned.
        val scrollInset = 8.dp
        Column(
            Modifier
                .fillMaxSize()
                .bleed(horizontal = scrollInset, vertical = scrollInset)
                .verticalScroll(rememberScrollState())
                .padding(scrollInset),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                SsButton(stringResource(R.string.common_back), onClick = onBack, modifier = Modifier.focusRequester(backFocus))
                Spacer(Modifier.width(16.dp))
                Text(stringResource(R.string.tools_title), style = SsType.Headline, color = SsColors.TextPrimary)
            }
            health()
            space()
            animations()
            extras()
            home()
        }
    }
}

/** Placeholder card until a wave-1 worker fills the slot. */
@Composable
fun ToolsPlaceholder(title: String) {
    SsCard(Modifier.fillMaxWidth()) {
        Text(
            stringResource(R.string.tools_placeholder, title),
            style = SsType.Body,
            color = SsColors.TextSecondary,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
        )
    }
}
