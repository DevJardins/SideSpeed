package io.github.devjardins.sidespeed.companion.android.update

import androidx.compose.runtime.Composable
import io.github.devjardins.sidespeed.companion.ui.SsButton
import io.github.devjardins.sidespeed.companion.update.CompanionUpdateState

/**
 * The About screen's update action on Android: "Update now" runs the in-app download, verify and
 * PackageInstaller install; "Cancel" stops a running download; "Try again" resumes the update
 * when a tag is known and re-checks otherwise.
 */
@Composable
fun AndroidUpdateAction(state: CompanionUpdateState, updater: CompanionUpdater) {
    when (state) {
        is CompanionUpdateState.Available -> SsButton("Update now", onClick = updater::startUpdate)
        is CompanionUpdateState.Downloading -> SsButton("Cancel", onClick = updater::cancel)
        is CompanionUpdateState.Failed -> SsButton("Try again", onClick = updater::retry)
        else -> Unit
    }
}
