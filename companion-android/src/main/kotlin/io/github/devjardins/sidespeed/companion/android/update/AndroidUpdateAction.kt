package io.github.devjardins.sidespeed.companion.android.update

import androidx.compose.runtime.Composable
import io.github.devjardins.sidespeed.companion.resources.Res
import io.github.devjardins.sidespeed.companion.resources.about_update_now
import io.github.devjardins.sidespeed.companion.resources.common_cancel
import io.github.devjardins.sidespeed.companion.resources.common_try_again
import io.github.devjardins.sidespeed.companion.ui.SsButton
import io.github.devjardins.sidespeed.companion.update.CompanionUpdateState
import org.jetbrains.compose.resources.stringResource

/**
 * The About screen's update action on Android: "Update now" runs the in-app download, verify and
 * PackageInstaller install; "Cancel" stops a running download; "Try again" resumes the update
 * when a tag is known and re-checks otherwise.
 */
@Composable
fun AndroidUpdateAction(state: CompanionUpdateState, updater: CompanionUpdater) {
    when (state) {
        is CompanionUpdateState.Available -> SsButton(stringResource(Res.string.about_update_now), onClick = updater::startUpdate)
        is CompanionUpdateState.Downloading -> SsButton(stringResource(Res.string.common_cancel), onClick = updater::cancel)
        is CompanionUpdateState.Failed -> SsButton(stringResource(Res.string.common_try_again), onClick = updater::retry)
        else -> Unit
    }
}
