package io.github.devjardins.sidespeed.companion.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.devjardins.sidespeed.companion.resources.Res
import io.github.devjardins.sidespeed.companion.resources.about_available
import io.github.devjardins.sidespeed.companion.resources.about_check_updates
import io.github.devjardins.sidespeed.companion.resources.about_checking
import io.github.devjardins.sidespeed.companion.resources.about_downloading
import io.github.devjardins.sidespeed.companion.resources.about_installing
import io.github.devjardins.sidespeed.companion.resources.about_licence_text
import io.github.devjardins.sidespeed.companion.resources.about_title
import io.github.devjardins.sidespeed.companion.resources.about_up_to_date
import io.github.devjardins.sidespeed.companion.resources.about_version
import io.github.devjardins.sidespeed.companion.resources.app_name
import io.github.devjardins.sidespeed.companion.ui.text.UiText
import io.github.devjardins.sidespeed.companion.ui.text.resolve
import io.github.devjardins.sidespeed.companion.update.CompanionUpdateState
import io.github.devjardins.sidespeed.companion.update.displayVersion
import org.jetbrains.compose.resources.stringResource

/**
 * The About screen: version, a manual update check, and the licences. The check runs when the
 * screen opens and when its button is pressed; there is no background polling. The platform's
 * [updateAction] slot renders below the status line ("Update now" on Android, "Download" on
 * desktop), or nothing when there is nothing to act on.
 */
@Composable
fun AboutScreen(
    aboutText: UiText = UiText.Res(Res.string.about_licence_text),
    versionName: String,
    update: CompanionUpdateState,
    onCheckUpdates: () -> Unit,
    onBack: () -> Unit,
    updateAction: @Composable () -> Unit,
) {
    LaunchedEffect(Unit) { onCheckUpdates() }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Header(stringResource(Res.string.about_title), onBack = onBack)
        SsCard {
            SsText(stringResource(Res.string.app_name), Ss.title)
            if (versionName.isNotEmpty()) {
                SsText(UiText.Res(Res.string.about_version, listOf(UiText.Ltr(versionName))).resolve())
            }
            SsButton(
                stringResource(Res.string.about_check_updates),
                onClick = onCheckUpdates,
                enabled = update != CompanionUpdateState.Checking &&
                    update !is CompanionUpdateState.Downloading &&
                    update != CompanionUpdateState.Installing,
            )
            // A newer release shows its line plus the platform's action below; UpToDate/Failed
            // keep their inline text, matching the TV About screen's style.
            val line = when (update) {
                CompanionUpdateState.Idle -> null
                CompanionUpdateState.Checking -> stringResource(Res.string.about_checking)
                CompanionUpdateState.UpToDate -> stringResource(Res.string.about_up_to_date)
                is CompanionUpdateState.Available ->
                    UiText.Res(Res.string.about_available, listOf(UiText.Ltr(displayVersion(update.tag)))).resolve()
                is CompanionUpdateState.Downloading ->
                    UiText.Res(Res.string.about_downloading, listOf("${update.percent}%")).resolve()
                CompanionUpdateState.Installing -> stringResource(Res.string.about_installing)
                is CompanionUpdateState.Failed -> update.reason.resolve()
            }
            line?.let { SsText(it) }
            updateAction()
        }
        SsCard { SsText(aboutText.resolve()) }
    }
}
