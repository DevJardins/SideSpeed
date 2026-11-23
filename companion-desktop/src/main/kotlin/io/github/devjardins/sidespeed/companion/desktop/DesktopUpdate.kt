package io.github.devjardins.sidespeed.companion.desktop

import io.github.devjardins.sidespeed.companion.update.CompanionUpdateCheck
import io.github.devjardins.sidespeed.companion.update.CompanionUpdateState
import io.github.devjardins.sidespeed.companion.update.RELEASES_API_URL
import io.github.devjardins.sidespeed.companion.update.RELEASES_PAGE_URL
import io.github.devjardins.sidespeed.companion.update.desktopDownloadUrl
import io.github.devjardins.sidespeed.companion.update.pickDesktopAsset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.net.URI
import java.net.URL

/** Check-only updater for the desktop Companion: no silent install, the Download button opens the browser. */
class DesktopUpdater(
    val installedVersion: String,
    private val fetchJson: suspend () -> String = ::fetchReleaseJson,
    private val scope: CoroutineScope,
    private val osName: String = System.getProperty("os.name").orEmpty(),
    private val arch: String = System.getProperty("os.arch").orEmpty(),
    private val dpkgPresent: () -> Boolean = ::defaultDpkgPresent,
    private val openUrl: (String) -> Unit = ::defaultOpenUrl,
) {
    private val check = CompanionUpdateCheck(fetchJson, installedVersion)

    private val _state = MutableStateFlow<CompanionUpdateState>(CompanionUpdateState.Idle)
    val state: StateFlow<CompanionUpdateState> = _state.asStateFlow()

    private var job: Job? = null

    /** Manual check for the About screen: runs when it opens and when its button is pressed. */
    fun checkNow() {
        if (_state.value is CompanionUpdateState.Checking) return
        _state.value = CompanionUpdateState.Checking
        job?.cancel()
        job = scope.launch {
            _state.value = check.checkNow()
        }
    }

    /** The download for this OS/arch, or the releases page when nothing fits. */
    fun downloadUrl(): String {
        val asset = runCatching { pickDesktopAsset(osName, arch, dpkgPresent()) }.getOrDefault(null)
        return if (asset != null) desktopDownloadUrl(asset) else RELEASES_PAGE_URL
    }

    /** Opens the download in the browser. */
    fun openDownload() {
        scope.launch(Dispatchers.IO) {
            runCatching { openUrl(downloadUrl()) }
        }
    }
}

/** Production release-metadata fetch, capped like the shared check expects. */
suspend fun fetchReleaseJson(): String = withContext(Dispatchers.IO) {
    val conn = (URL(RELEASES_API_URL).openConnection() as java.net.HttpURLConnection).apply {
        connectTimeout = 15_000
        readTimeout = 30_000
        setRequestProperty("Accept", "application/vnd.github+json")
    }
    val code = conn.responseCode
    if (code !in 200..299) {
        conn.disconnect()
        throw IllegalStateException("HTTP $code from $RELEASES_API_URL")
    }
    val cap = 256 * 1024L
    conn.inputStream.use { body ->
        val out = StringBuilder()
        val buf = ByteArray(8 * 1024)
        var total = 0L
        while (true) {
            val n = body.read(buf)
            if (n < 0) break
            total += n
            if (total > cap) throw IllegalStateException("Release body exceeds cap")
            out.append(String(buf, 0, n, Charsets.UTF_8))
        }
        out.toString()
    }
}

/** True when dpkg exists, meaning this Linux box installs the .deb; otherwise it gets the .rpm. */
internal fun defaultDpkgPresent(): Boolean = runCommand("dpkg", "--version").isNotBlank()

/** Opens a URL in the default browser. */
internal fun defaultOpenUrl(url: String) {
    if (Desktop.isDesktopSupported()) {
        Desktop.getDesktop().browse(URI(url))
    }
}
