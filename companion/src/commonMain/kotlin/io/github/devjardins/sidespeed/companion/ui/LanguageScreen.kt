package io.github.devjardins.sidespeed.companion.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import io.github.devjardins.sidespeed.companion.l10n.LanguageController
import io.github.devjardins.sidespeed.companion.resources.Res
import io.github.devjardins.sidespeed.companion.resources.settings_language
import io.github.devjardins.sidespeed.companion.resources.settings_language_not_complete
import io.github.devjardins.sidespeed.companion.resources.settings_language_same_as_device
import io.github.devjardins.sidespeed.companion.resources.settings_language_selected
import io.github.devjardins.sidespeed.l10n.languageOptions
import io.github.devjardins.sidespeed.l10n.nativeLanguageName
import org.jetbrains.compose.resources.stringResource

/** The Language screen: one row per offered language, the current one marked and focused. */
@Composable
fun LanguageScreen(controller: LanguageController, onBack: () -> Unit) {
    val chosen by controller.chosen.collectAsState()
    val options = languageOptions(controller.shipped, chosen, ::nativeLanguageName)
    // Give keyboard and D-pad input a starting point: the current choice takes focus when the screen opens.
    val selectedFocus = remember { FocusRequester() }
    val selected = options.firstOrNull { it.selected }?.tag
    LaunchedEffect(selected) {
        runCatching { selectedFocus.requestFocus() }
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Header(stringResource(Res.string.settings_language), onBack = onBack)
        options.forEach { option ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val label = option.name
                    ?: stringResource(Res.string.settings_language_same_as_device)
                SsButton(
                    if (option.selected) stringResource(Res.string.settings_language_selected, label) else label,
                    onClick = { controller.choose(option.tag) },
                    primary = option.selected,
                    modifier = if (option.selected) Modifier.focusRequester(selectedFocus) else Modifier,
                )
                if (option.notComplete) {
                    SsText(stringResource(Res.string.settings_language_not_complete), Ss.caption)
                }
            }
        }
    }
}
