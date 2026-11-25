package io.github.devjardins.sidespeed.companion.android.update

import android.content.pm.PackageInstaller
import io.github.devjardins.sidespeed.companion.resources.Res
import io.github.devjardins.sidespeed.companion.resources.update_cancelled
import io.github.devjardins.sidespeed.companion.resources.update_install_failed
import io.github.devjardins.sidespeed.companion.resources.update_install_failed_detail
import io.github.devjardins.sidespeed.companion.ui.text.UiText
import io.github.devjardins.sidespeed.companion.update.CompanionUpdateState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CompanionInstallStatusTest {
    @Test fun pendingUserActionNeedsNoStateChange() {
        assertNull(installStatusToState(PackageInstaller.STATUS_PENDING_USER_ACTION, null))
        assertNull(installStatusToState(PackageInstaller.STATUS_PENDING_USER_ACTION, "user action required"))
    }

    @Test fun successNeedsNoStateChange() {
        // The process is replaced; the About screen never sees this.
        assertNull(installStatusToState(PackageInstaller.STATUS_SUCCESS, null))
        assertNull(installStatusToState(PackageInstaller.STATUS_SUCCESS, "done"))
    }

    @Test fun abortedMeansCancelled() {
        assertEquals(
            CompanionUpdateState.Failed(UiText.Res(Res.string.update_cancelled)),
            installStatusToState(PackageInstaller.STATUS_FAILURE_ABORTED, null),
        )
        assertEquals(
            CompanionUpdateState.Failed(UiText.Res(Res.string.update_cancelled)),
            installStatusToState(PackageInstaller.STATUS_FAILURE_ABORTED, "aborted"),
        )
    }

    @Test fun failureWithoutMessageHasNoSuffix() {
        assertEquals(
            CompanionUpdateState.Failed(UiText.Res(Res.string.update_install_failed)),
            installStatusToState(PackageInstaller.STATUS_FAILURE, null),
        )
        assertEquals(
            CompanionUpdateState.Failed(UiText.Res(Res.string.update_install_failed)),
            installStatusToState(PackageInstaller.STATUS_FAILURE, "  "),
        )
    }

    @Test fun failureCarriesSystemMessage() {
        assertEquals(
            CompanionUpdateState.Failed(
                UiText.Res(Res.string.update_install_failed_detail, listOf(UiText.Raw("INSTALL_FAILED_VERSION_DOWNGRADE"))),
            ),
            installStatusToState(PackageInstaller.STATUS_FAILURE_INVALID, "INSTALL_FAILED_VERSION_DOWNGRADE"),
        )
        assertEquals(
            CompanionUpdateState.Failed(
                UiText.Res(Res.string.update_install_failed_detail, listOf(UiText.Raw("no space"))),
            ),
            installStatusToState(PackageInstaller.STATUS_FAILURE_STORAGE, "no space"),
        )
    }

    @Test fun unknownStatusFails() {
        assertEquals(
            CompanionUpdateState.Failed(UiText.Res(Res.string.update_install_failed)),
            installStatusToState(999, null),
        )
    }

    @Test fun installTimeoutIsFiveMinutes() {
        assertEquals(5 * 60 * 1000L, INSTALL_TIMEOUT_MS)
    }
}
