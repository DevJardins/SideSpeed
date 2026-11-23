package io.github.devjardins.sidespeed.companion.desktop

import io.github.devjardins.sidespeed.companion.update.CompanionUpdateState
import io.github.devjardins.sidespeed.companion.update.LATEST_DOWNLOAD_BASE_URL
import io.github.devjardins.sidespeed.companion.update.LINUX_DEB_ASSET
import io.github.devjardins.sidespeed.companion.update.LINUX_RPM_ASSET
import io.github.devjardins.sidespeed.companion.update.MACOS_ASSET
import io.github.devjardins.sidespeed.companion.update.RELEASES_PAGE_URL
import io.github.devjardins.sidespeed.companion.update.WINDOWS_ASSET
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopUpdateTest {
    private fun updater(
        json: String = """{"tag_name":"v0.8.0"}""",
        osName: String = "Mac OS X",
        arch: String = "aarch64",
        dpkgPresent: Boolean = false,
        opened: MutableList<String> = mutableListOf(),
    ) = DesktopUpdater(
        installedVersion = "0.7.0",
        fetchJson = { json },
        scope = CoroutineScope(Dispatchers.Unconfined),
        osName = osName,
        arch = arch,
        dpkgPresent = { dpkgPresent },
        openUrl = opened::add,
    )

    private fun checked(update: DesktopUpdater): CompanionUpdateState {
        runBlocking { delay(50) }
        return update.state.value
    }

    @Test fun newerReleaseIsAvailable() {
        val update = updater()
        update.checkNow()
        assertEquals(CompanionUpdateState.Available("v0.8.0"), checked(update))
    }

    @Test fun sameReleaseIsUpToDate() {
        val update = updater("""{"tag_name":"v0.7.0"}""")
        update.checkNow()
        assertEquals(CompanionUpdateState.UpToDate, checked(update))
    }

    @Test fun preReleaseLatestIsUpToDate() {
        val update = updater("""{"tag_name":"v0.8.0-beta"}""")
        update.checkNow()
        assertEquals(CompanionUpdateState.UpToDate, checked(update))
    }

    @Test fun fetchFailureReportsFailed() {
        val update = DesktopUpdater(
            installedVersion = "0.7.0",
            fetchJson = { throw IllegalStateException("offline") },
            scope = CoroutineScope(Dispatchers.Unconfined),
        )
        update.checkNow()
        val state = checked(update)
        assertTrue(state is CompanionUpdateState.Failed)
    }

    @Test fun macOpensDmg() {
        val opened = mutableListOf<String>()
        runBlocking { updater(opened = opened).openDownload() }
        // openDownload launches on IO: wait for the fake to record.
        runBlocking { delay(200) }
        assertEquals(listOf("$LATEST_DOWNLOAD_BASE_URL/$MACOS_ASSET"), opened)
    }

    @Test fun windowsOpensMsi() {
        val opened = mutableListOf<String>()
        runBlocking { updater(osName = "Windows 11", arch = "amd64", opened = opened).openDownload() }
        runBlocking { delay(200) }
        assertEquals(listOf("$LATEST_DOWNLOAD_BASE_URL/$WINDOWS_ASSET"), opened)
    }

    @Test fun linuxDebWhenDpkg() {
        val update = updater(osName = "Linux", arch = "x86_64", dpkgPresent = true)
        assertEquals("$LATEST_DOWNLOAD_BASE_URL/$LINUX_DEB_ASSET", update.downloadUrl())
    }

    @Test fun linuxRpmWithoutDpkg() {
        val update = updater(osName = "Linux", arch = "x86_64", dpkgPresent = false)
        assertEquals("$LATEST_DOWNLOAD_BASE_URL/$LINUX_RPM_ASSET", update.downloadUrl())
    }

    @Test fun unknownOsOpensReleasesPage() {
        val update = updater(osName = "FreeBSD", arch = "amd64")
        assertEquals(RELEASES_PAGE_URL, update.downloadUrl())
    }
}
