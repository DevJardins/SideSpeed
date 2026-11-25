package io.github.devjardins.sidespeed.companion.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.devjardins.sidespeed.companion.l10n.LanguageController
import io.github.devjardins.sidespeed.companion.resources.Res
import io.github.devjardins.sidespeed.companion.resources.about_title
import io.github.devjardins.sidespeed.companion.resources.common_cancel
import io.github.devjardins.sidespeed.companion.resources.common_remove
import io.github.devjardins.sidespeed.companion.resources.common_save
import io.github.devjardins.sidespeed.companion.resources.settings_add_trusted_network
import io.github.devjardins.sidespeed.companion.resources.settings_check_every
import io.github.devjardins.sidespeed.companion.resources.settings_interval_hours
import io.github.devjardins.sidespeed.companion.resources.settings_interval_minutes
import io.github.devjardins.sidespeed.companion.resources.settings_language
import io.github.devjardins.sidespeed.companion.resources.settings_notifications
import io.github.devjardins.sidespeed.companion.resources.settings_notify_attention
import io.github.devjardins.sidespeed.companion.resources.settings_rename
import io.github.devjardins.sidespeed.companion.resources.settings_start_at_login
import io.github.devjardins.sidespeed.companion.resources.settings_tailnet_row
import io.github.devjardins.sidespeed.companion.resources.settings_tailnets
import io.github.devjardins.sidespeed.companion.resources.settings_tailnets_note
import io.github.devjardins.sidespeed.companion.resources.settings_tailscale_disconnected
import io.github.devjardins.sidespeed.companion.resources.settings_title
import io.github.devjardins.sidespeed.companion.resources.settings_trusted_networks
import io.github.devjardins.sidespeed.companion.store.CompanionSettings
import io.github.devjardins.sidespeed.companion.ui.text.UiText
import io.github.devjardins.sidespeed.companion.ui.text.resolve
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

@Composable
fun SettingsScreen(
    state: CompanionUiState,
    desktop: Boolean,
    onSettings: (CompanionSettings) -> Unit,
    onAddNetwork: () -> Unit,
    onRenameNetwork: (String, String) -> Unit,
    onRemoveNetwork: (String) -> Unit,
    onRemoveTailnet: (String) -> Unit,
    onAbout: () -> Unit,
    onBack: () -> Unit,
    language: LanguageController? = null,
    onLanguage: () -> Unit = {},
    focusLanguage: Boolean = false,
    onConsumeLanguageFocus: () -> Unit = {},
) {
    val s = state.settings
    // Coming back from the Language screen puts focus on its button, so keyboard
    // and D-pad input continue where the user left off.
    val langFocus = remember { FocusRequester() }
    LaunchedEffect(focusLanguage) {
        if (focusLanguage) {
            runCatching { langFocus.requestFocus() }
            onConsumeLanguageFocus()
        }
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Header(stringResource(Res.string.settings_title), onBack = onBack)
        SsCard {
            SsText(stringResource(Res.string.settings_trusted_networks), Ss.heading)
            state.trustedNetworks.forEach { n ->
                var editing by remember(n.id) { mutableStateOf(false) }
                if (editing) {
                    var label by remember(n.id) { mutableStateOf(n.name) }
                    val focus = remember { FocusRequester() }
                    LaunchedEffect(Unit) { focus.requestFocus() }
                    val save = { onRenameNetwork(n.id, label); editing = false }
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SsTextField(label, { label = it }, n.id, Modifier.focusRequester(focus), ImeAction.Done, save)
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            SsButton(stringResource(Res.string.common_save), save, primary = true)
                            SsButton(stringResource(Res.string.common_cancel), { editing = false })
                        }
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        SsText(n.displayName, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        SsButton(stringResource(Res.string.settings_rename), { editing = true })
                        SsButton(stringResource(Res.string.common_remove), { onRemoveNetwork(n.id) })
                    }
                }
            }
            if (state.currentNetwork != null) {
                SsButton(
                    stringResource(Res.string.settings_add_trusted_network),
                    onAddNetwork,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
        // Desktop only: Android has no Tailscale source, so `tailnet` is null there and none is ever trusted.
        if (state.tailnet != null || state.trustedOverlays.isNotEmpty()) SsCard {
            SsText(stringResource(Res.string.settings_tailnets), Ss.heading)
            SsText(stringResource(Res.string.settings_tailnets_note), Ss.caption)
            state.trustedOverlays.forEach { o ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    SsText(
                        UiText.Res(
                            Res.string.settings_tailnet_row,
                            listOf(UiText.Fsi(o.name), UiText.Ltr(o.tailnet)),
                        ).resolve(),
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    SsButton(stringResource(Res.string.common_remove), { onRemoveTailnet(o.id) })
                }
            }
            val t = state.tailnet
            if (t == null || !t.ready) SsText(stringResource(Res.string.settings_tailscale_disconnected), Ss.caption)
        }
        SsCard {
            SsText(stringResource(Res.string.settings_notifications), Ss.heading)
            SsSwitchRow(stringResource(Res.string.settings_notify_attention), s.notifications) {
                onSettings(s.copy(notifications = it))
            }
        }
        SsCard {
            SsText(stringResource(Res.string.settings_check_every), Ss.heading)
            SsSegmented(
                listOf(
                    pluralStringResource(Res.plurals.settings_interval_minutes, 15, 15),
                    pluralStringResource(Res.plurals.settings_interval_hours, 1, 1),
                ),
                if (s.intervalMinutes == 60) 1 else 0,
            ) {
                onSettings(s.copy(intervalMinutes = if (it == 1) 60 else 15))
            }
            if (desktop) {
                SsSwitchRow(stringResource(Res.string.settings_start_at_login), s.startAtLogin) {
                    onSettings(s.copy(startAtLogin = it))
                }
            }
        }
        if (language != null) {
            SsButton(
                stringResource(Res.string.settings_language),
                onLanguage,
                modifier = Modifier.focusRequester(langFocus),
            )
        }
        SsButton(stringResource(Res.string.about_title), onAbout, modifier = Modifier.padding(bottom = 8.dp))
    }
}
