package io.github.devjardins.sidespeed.update

import android.content.pm.PackageManager
import android.os.Build
import io.github.devjardins.sidespeed.helper.InstallOutcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

/** The update banner's states. */
sealed interface UpdateUiState {
    data object Available : UpdateUiState
    data object Busy : UpdateUiState
    data class Downloading(val percent: Int) : UpdateUiState
    data object Installing : UpdateUiState
    data class Failed(val reason: String) : UpdateUiState
}

private const val UPDATE_DIR = "update"
private const val APK_NAME = "SideSpeed.apk"
private const val SUMS_NAME = "SHA256SUMS.txt"

/**
 * How long [SelfUpdater] waits for the installed versionCode to rise after the helper reports
 * (or drops) an install. A real install kills our process, so surviving means unconfirmed.
 */
const val CONFIRM_TIMEOUT_MS = 60_000L

/** Delay between installed-version polls while confirming an update. */
const val CONFIRM_POLL_MS = 1_000L

/**
 * "Update now" flow: downloads the release APK and checksums, verifies them, and installs
 * through the helper (or the fallback installer when the helper is down). Files live in
 * `cacheDir/update/` and are deleted after every outcome and on app start ([clearStaleFiles]).
 */
class SelfUpdater(
    private val download: UpdateDownload,
    private val readApkFacts: (File) -> ApkFacts?,
    private val installed: () -> Pair<Long, Set<String>>,
    private val helperUp: suspend () -> Boolean,
    private val helperInstall: suspend (File) -> InstallOutcome,
    private val fallbackInstall: suspend (File) -> InstallOutcome,
    private val cacheDir: File,
    private val busy: Flow<Boolean>,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val confirmTimeoutMs: Long = CONFIRM_TIMEOUT_MS,
    private val confirmPollMs: Long = CONFIRM_POLL_MS,
) {
    private val _state = MutableStateFlow<UpdateUiState>(UpdateUiState.Available)
    val state: StateFlow<UpdateUiState> = _state.asStateFlow()

    private var job: Job? = null

    init {
        // Busy is derived from the busy flow, never sticky: it only ever flips Available<->Busy,
        // so an ended job returns the banner to Available and Update now works again.
        scope.launch {
            busy.collect { b ->
                _state.update { cur ->
                    when {
                        b && cur == UpdateUiState.Available -> UpdateUiState.Busy
                        !b && cur == UpdateUiState.Busy -> UpdateUiState.Available
                        else -> cur
                    }
                }
            }
        }
    }

    /** Starts the update for release [tag], unless one is already running or the TV is busy. */
    @Synchronized
    fun start(tag: String) {
        if (job?.isActive == true) return
        job = scope.launch {
            if (busy.first()) {
                _state.value = UpdateUiState.Busy
                return@launch
            }
            try {
                execute(tag)
            } catch (e: CancellationException) {
                deleteUpdateFiles()
                _state.value = UpdateUiState.Available
                throw e
            } catch (e: Exception) {
                deleteUpdateFiles()
                _state.value = UpdateUiState.Failed("Update failed.")
            }
        }
    }

    /** Cancels a running download; the install itself cannot be cancelled once started. */
    fun cancel() {
        job?.cancel()
    }

    /** Deletes the update dir's contents; called on app start and after every outcome. */
    fun clearStaleFiles() {
        deleteUpdateFiles()
    }

    private suspend fun execute(tag: String) {
        val apkUrl = releaseAssetUrl(tag, APK_NAME)
            ?: return fail("Not a valid release version.")
        val sumsUrl = releaseAssetUrl(tag, SUMS_NAME)
            ?: return fail("Not a valid release version.")
        _state.value = UpdateUiState.Downloading(0)
        val sumsText = download.fetchText(sumsUrl).getOrElse { return fail("Could not download the checksums.") }
        val expected = parseSha256Sums(sumsText, APK_NAME)
            ?: return fail("No checksum found for $APK_NAME.")
        val dir = File(cacheDir, UPDATE_DIR).apply { mkdirs() }
        val apk = File(dir, APK_NAME)
        download.downloadTo(apkUrl, apk, onProgress = {
            _state.value = UpdateUiState.Downloading(it)
        }).getOrElse { return fail("Download failed.") }
        val (installedVersion, installedSigners) = installed()
        when (val verdict = verifyUpdate(sha256Hex(apk), expected, readApkFacts(apk), installedVersion, installedSigners)) {
            is VerifyResult.Ok -> Unit
            is VerifyResult.Rejected -> return fail(verdict.reason)
        }
        _state.value = UpdateUiState.Installing
        // A down helper reports HelperDown without touching the APK, so the fallback below
        // runs exactly once whether the helper was never up or died on this attempt.
        val outcome = if (helperUp()) helperInstall(apk) else InstallOutcome.HelperDown
        when (outcome) {
            // The helper died before or during the stream, or an impostor read the body and
            // closed: the APK is untouched, so hand it to the fallback installer as-is.
            is InstallOutcome.HelperDown -> return finishFallback(apk)
            is InstallOutcome.Disconnected -> {
                // A real install kills our process, so if we are still alive the install may
                // still land: confirm via the installed version before giving up on the helper.
                if (awaitVersionRise(installedVersion)) {
                    deleteUpdateFiles() // the app is replaced from here on
                } else {
                    return finishFallback(apk)
                }
            }
            is InstallOutcome.Started -> {
                // END:0 is the helper's word, not proof: a stale-port impostor says it too.
                // Only a risen versionCode means the update landed; anything else is a failure.
                if (awaitVersionRise(installedVersion)) {
                    deleteUpdateFiles() // the app is replaced from here on
                } else {
                    return fail("The update didn't install.")
                }
            }
            is InstallOutcome.Failed -> return fail(outcome.lastLine ?: "Install failed.")
        }
    }

    /**
     * Hands [apk] to the system installer. The APK must still be present: this runs only when
     * the helper path reported [InstallOutcome.HelperDown]/[InstallOutcome.Disconnected] without
     * a confirmed install, and nothing above deletes files first.
     */
    private suspend fun finishFallback(apk: File) {
        when (val fb = fallbackInstall(apk)) {
            is InstallOutcome.Started -> deleteUpdateFiles() // session committed; system takes over
            is InstallOutcome.Disconnected -> return fail("Helper not running.")
            is InstallOutcome.Failed -> return fail(fb.lastLine ?: "Install failed.")
            is InstallOutcome.HelperDown -> return fail("Helper not running.")
        }
    }

    /**
     * True when the installed versionCode rises above [baseline] within [confirmTimeoutMs].
     * A real helper install replaces our process, so surviving the stream at all means the
     * install is still unconfirmed; only a rise counts, never the helper's word alone.
     */
    private suspend fun awaitVersionRise(baseline: Long): Boolean {
        val deadline = System.currentTimeMillis() + confirmTimeoutMs
        while (true) {
            val current = try {
                installed().first
            } catch (_: Exception) {
                baseline
            }
            if (current > baseline) return true
            if (System.currentTimeMillis() >= deadline) return false
            delay(confirmPollMs)
        }
    }

    private suspend fun fail(reason: String) {
        deleteUpdateFiles()
        _state.value = UpdateUiState.Failed(reason)
    }

    private fun deleteUpdateFiles() {
        File(cacheDir, UPDATE_DIR).listFiles()?.forEach { it.deleteRecursively() }
    }
}

/** Lowercase hex of bytes, for certificate fingerprints. */
internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

/** The installed app's signing-certificate SHA-256 set, or empty when unreadable. */
fun installedSignerSha256(pm: PackageManager, packageName: String): Set<String> {
    return try {
        val info = if (Build.VERSION.SDK_INT >= 28) {
            pm.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(packageName, PackageManager.GET_SIGNATURES)
        }
        val signatures = if (Build.VERSION.SDK_INT >= 28) {
            info.signingInfo?.apkContentsSigners
        } else {
            @Suppress("DEPRECATION")
            info.signatures
        }?.toList() ?: return emptySet()
        val md = java.security.MessageDigest.getInstance("SHA-256")
        signatures.mapTo(mutableSetOf()) { md.digest(it.toByteArray()).toHex() }
    } catch (_: Exception) {
        emptySet()
    }
}

/**
 * Production [SelfUpdater.readApkFacts]: package, version and signer set from the APK archive,
 * or null when the file is not a parseable APK.
 */
fun readApkFacts(pm: PackageManager, file: File): ApkFacts? {
    return try {
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES
        else PackageManager.GET_SIGNATURES
        val info = pm.getPackageArchiveInfo(file.absolutePath, flags) ?: return null
        val packageName = info.packageName ?: return null
        val versionCode = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
        val signatures = if (Build.VERSION.SDK_INT >= 28) {
            info.signingInfo?.apkContentsSigners
        } else {
            @Suppress("DEPRECATION")
            info.signatures
        }?.toList() ?: return null
        val md = java.security.MessageDigest.getInstance("SHA-256")
        ApkFacts(packageName, versionCode, signatures.mapTo(mutableSetOf()) { md.digest(it.toByteArray()).toHex() })
    } catch (_: Exception) {
        null
    }
}
