package io.github.devjardins.sidespeed.ui.about

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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import io.github.devjardins.sidespeed.R
import io.github.devjardins.sidespeed.l10n.LanguageOption
import io.github.devjardins.sidespeed.ui.theme.SsButton
import io.github.devjardins.sidespeed.ui.theme.SsColors
import io.github.devjardins.sidespeed.ui.theme.SsScreen
import io.github.devjardins.sidespeed.ui.theme.SsType
import io.github.devjardins.sidespeed.ui.theme.bleed

/** Label for a language row: the device label for "Same as device", else the native name. */
fun languageRowLabel(option: LanguageOption, deviceLabel: String): String {
    val tag = option.tag
    if (tag == null) return deviceLabel
    return option.name ?: tag
}

/** The Language screen: one row per language, the current one marked and focused. */
@Composable
fun LanguageScreen(
    deviceLabel: String,
    options: List<LanguageOption>,
    onChoose: (String?) -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    // Give the D-pad a starting point: the current language takes focus when the screen opens.
    val selectedFocus = remember { FocusRequester() }
    val listState = rememberLazyListState()
    LaunchedEffect(options) {
        // The screen we came from releases D-pad focus a frame after it leaves;
        // wait that out so this request wins, as in HomeScreen.
        withFrameNanos { }
        withFrameNanos { }
        // A lazy row off screen is not composed yet, so bring it into view first.
        val index = options.indexOfFirst { it.selected }
        if (index >= 0) {
            listState.scrollToItem(index)
            withFrameNanos { }
        }
        runCatching { selectedFocus.requestFocus() }
    }
    SsScreen {
        // Same scroll treatment as AboutScreen: bleed the column so the focus ring
        // is never clipped at the edges.
        val scrollInset = 8.dp
        Column(
            Modifier
                .fillMaxSize()
                .bleed(horizontal = scrollInset, vertical = scrollInset)
                .padding(scrollInset),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                SsButton(stringResource(R.string.common_back), onClick = onBack)
                Spacer(Modifier.width(16.dp))
                Text(stringResource(R.string.about_language), style = SsType.Headline, color = SsColors.TextPrimary)
            }
            LazyColumn(
                state = listState,
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(vertical = 4.dp, horizontal = 4.dp),
            ) {
                items(options, key = { it.tag ?: "device" }) { option ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        val label = languageRowLabel(option, deviceLabel)
                        SsButton(
                            if (option.selected) stringResource(R.string.about_language_selected, label) else label,
                            onClick = { onChoose(option.tag) },
                            selected = option.selected,
                            modifier = if (option.selected) Modifier.focusRequester(selectedFocus) else Modifier,
                        )
                        if (option.notComplete) {
                            Text(
                                stringResource(R.string.about_language_not_complete),
                                style = SsType.Caption,
                                color = SsColors.TextSecondary,
                            )
                        }
                    }
                }
            }
        }
    }
}
