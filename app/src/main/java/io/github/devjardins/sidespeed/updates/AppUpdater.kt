package io.github.devjardins.sidespeed.updates

import io.github.devjardins.sidespeed.R
import io.github.devjardins.sidespeed.helper.HelperMain
import io.github.devjardins.sidespeed.helper.InstallOutcome
import io.github.devjardins.sidespeed.ui.text.UiText
import io.github.devjardins.sidespeed.update.ApkFacts
import io.github.devjardins.sidespeed.update.CONFIRM_POLL_MS
import io.github.devjardins.sidespeed.update.CONFIRM_TIMEOUT_MS
import io.github.devjardins.sidespeed.update.UpdateDownload
import io.github.devjardins.sidespeed.update.VerifyResult
import io.github.devjardins.sidespeed.update.sha256Hex
import io.github.devjardins.sidespeed.update.verifyUpdate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/** The per-app update flow's states. */
sealed interface AppUpdateUiState {
    data object Idle : AppUpdateUiState
    data class Downloading(val percent: Int) : AppUpdateUiState
    data object Checking : AppUpdateUiState
    data object Installing : AppUpdateUiState
    /**
     * The helper is down, so the verified APK went to the system installer instead.
     * The TV asks the user to confirm there; nothing more happens in the app.
     */
    data object AwaitingConfirmation : AppUpdateUiState
    data class Done(val versionName: String) : AppUpdateUiState
    data class Failed(val reason: UiText) : AppUpdateUiState
}

private const val APP_UPDATES_DIR = "app-updates"

/**
 * "Update now" flow for one sideloaded app: downloads the picked variant, verifies it is a
 * genuine update of the installed app (same package, listed version, same signers), and installs
 * it through the helper (or the system installer when the helper is down). Files live in
 * `cacheDir/app-updates/` and are deleted after every outcome. One update at a time across
 * the app: [start] is a no-op while one runs.
 */
class AppUpdater(
    private val download: UpdateDownload,
    private val readApkFacts: (File) -> ApkFacts?,
    private val installed: (pkg: String) -> Pair<Long, Set<String>>,
    private val helperUp: suspend () -> Boolean,
    private val helperInstall: suspend (pkg: String, apk: File) -> InstallOutcome,
    private val fallbackInstall: suspend (apk: File) -> InstallOutcome,
    private val cacheDir: File,
    private val freeSpace: () -> Long = { cacheDir.usableSpace },
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val confirmTimeoutMs: Long = CONFIRM_TIMEOUT_MS,
    private val confirmPollMs: Long = CONFIRM_POLL_MS,
    // How long a cut connection still gets to land: big APKs can install for minutes
    // after the stream ends, so this shares the helper's own install deadline.
    private val disconnectTimeoutMs: Long = HelperMain.INSTALL_WAIT_MS,
) {
    private val _state = MutableStateFlow<AppUpdateUiState>(AppUpdateUiState.Idle)
    val state: StateFlow<AppUpdateUiState> = _state.asStateFlow()

    /**
     * The package the state belongs to, or null when no update has started.
     * Reopening the same app keeps its live state; the Compile screen resets
     * only when a different package's update is idle or finished.
     */
    private val _pkg = MutableStateFlow<String?>(null)
    val pkg: StateFlow<String?> = _pkg.asStateFlow()

    private var job: Job? = null

    /** Starts the update, unless one is already running. */
    @Synchronized
    fun start(pkg: String, variant: ApkVariant, versionName: String) {
        if (job?.isActive == true) return
        _pkg.value = pkg
        job = scope.launch {
            try {
                execute(pkg, variant, versionName)
            } catch (e: CancellationException) {
                deleteUpdateFiles()
                _state.value = AppUpdateUiState.Idle
                throw e
            } catch (e: Exception) {
                deleteUpdateFiles()
                _state.value = AppUpdateUiState.Failed(UiText.Res(R.string.update_error_failed))
            }
        }
    }

    /** Cancels a running update; the install itself cannot be cancelled once started. */
    fun cancel() {
        job?.cancel()
    }

    /** Back to idle, e.g. when the Compile screen opens for another app. Cancels a running update. */
    fun reset() {
        job?.cancel()
        _pkg.value = null
        _state.value = AppUpdateUiState.Idle
    }

    /** Deletes the update dir's contents; called on app start and after every outcome. */
    fun clearStaleFiles() {
        deleteUpdateFiles()
    }

    private suspend fun execute(pkg: String, variant: ApkVariant, versionName: String) {
        if (freeSpace() < variant.size * 2) return fail(UiText.Res(R.string.update_error_no_space))
        _state.value = AppUpdateUiState.Downloading(0)
        val dir = File(cacheDir, APP_UPDATES_DIR).apply { mkdirs() }
        val apk = File(dir, "$pkg.apk")
        download.downloadTo(variant.url, apk, maxBytes = variant.size, onProgress = {
            _state.value = AppUpdateUiState.Downloading(it)
        }).getOrElse { return fail(UiText.Res(R.string.update_error_download_failed)) }
        _state.value = AppUpdateUiState.Checking
        val (installedVersion, installedSigners) = try {
            installed(pkg)
        } catch (_: Exception) {
            return fail(UiText.Res(R.string.update_error_read_installed))
        }
        when (
            val verdict = verifyUpdate(
                sha256Hex(apk), variant.sha256, readApkFacts(apk),
                installedVersion, installedSigners, pkg, variant.versionCode,
            )
        ) {
            is VerifyResult.Ok -> Unit
            is VerifyResult.Rejected -> return fail(verdict.reason)
        }
        _state.value = AppUpdateUiState.Installing
        // A down helper reports HelperDown without touching the APK, so the fallback below
        // runs exactly once whether the helper was never up or died on this attempt.
        val outcome = if (helperUp()) helperInstall(pkg, apk) else InstallOutcome.HelperDown
        when (outcome) {
            // The helper died before any body byte: the APK is untouched, so hand it to the
            // system installer instead. This fallback is only for HelperDown, never for a cut
            // connection, where the helper's install may still be running.
            is InstallOutcome.HelperDown -> return finishFallback(apk)
            is InstallOutcome.Disconnected -> {
                // The install may still land minutes later (big APKs install slowly), so wait
                // it out: only a risen versionCode makes Done, otherwise Failed, never the
                // system installer on top of a running helper install.
                if (awaitVersionRise(pkg, variant.versionCode, disconnectTimeoutMs)) {
                    deleteUpdateFiles()
                    _state.value = AppUpdateUiState.Done(versionName)
                } else {
                    return fail(UiText.Res(R.string.update_error_confirm_timeout))
                }
            }
            is InstallOutcome.Started -> {
                // END:0 is the helper's word, not proof: only a risen versionCode means the
                // update landed; anything else is a failure.
                if (awaitVersionRise(pkg, variant.versionCode, confirmTimeoutMs)) {
                    deleteUpdateFiles()
                    _state.value = AppUpdateUiState.Done(versionName)
                } else {
                    return fail(UiText.Res(R.string.update_error_didnt_install))
                }
            }
            is InstallOutcome.Failed -> return fail(
                outcome.lastLine?.let(UiText::Raw) ?: UiText.Res(R.string.update_error_install_failed),
            )
        }
    }

    /**
     * Hands [apk] to the system installer. The APK must still be present: this runs only when
     * the helper was down before any body byte ([InstallOutcome.HelperDown]), and nothing
     * above deletes files first.
     */
    private suspend fun finishFallback(apk: File) {
        when (val fb = fallbackInstall(apk)) {
            is InstallOutcome.Started -> {
                deleteUpdateFiles() // session committed; the system takes over
                _state.value = AppUpdateUiState.AwaitingConfirmation
            }
            is InstallOutcome.Disconnected -> return fail(UiText.Res(R.string.update_error_helper_down))
            is InstallOutcome.Failed -> return fail(
                fb.lastLine?.let(UiText::Raw) ?: UiText.Res(R.string.update_error_install_failed),
            )
            is InstallOutcome.HelperDown -> return fail(UiText.Res(R.string.update_error_helper_down))
        }
    }

    /**
     * True when the installed versionCode reaches [target] within [timeoutMs].
     * Only a rise counts, never the helper's word alone.
     */
    private suspend fun awaitVersionRise(pkg: String, target: Long, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val current = try {
                installed(pkg).first
            } catch (_: Exception) {
                -1L
            }
            if (current >= target) return true
            if (System.currentTimeMillis() >= deadline) return false
            delay(confirmPollMs)
        }
    }

    private suspend fun fail(reason: UiText) {
        deleteUpdateFiles()
        _state.value = AppUpdateUiState.Failed(reason)
    }

    private fun deleteUpdateFiles() {
        // Best effort: deleteRecursively throws AssertionError if a file vanishes mid-walk, which must not
        // crash the update coroutine.
        File(cacheDir, APP_UPDATES_DIR).listFiles()?.forEach { runCatching { it.deleteRecursively() } }
    }
}
