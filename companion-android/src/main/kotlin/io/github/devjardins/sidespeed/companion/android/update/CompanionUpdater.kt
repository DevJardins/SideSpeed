package io.github.devjardins.sidespeed.companion.android.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import io.github.devjardins.sidespeed.companion.update.ApkFacts
import io.github.devjardins.sidespeed.companion.update.COMPANION_APK_NAME
import io.github.devjardins.sidespeed.companion.update.COMPANION_SUMS_NAME
import io.github.devjardins.sidespeed.companion.update.COMPANION_UPDATE_CHECK_MAX_BYTES
import io.github.devjardins.sidespeed.companion.update.CompanionUpdateCheck
import io.github.devjardins.sidespeed.companion.update.CompanionUpdateState
import io.github.devjardins.sidespeed.companion.update.CompanionVerifyResult
import io.github.devjardins.sidespeed.companion.update.LATEST_DOWNLOAD_BASE_URL
import io.github.devjardins.sidespeed.companion.update.RELEASES_API_URL
import io.github.devjardins.sidespeed.companion.update.parseSha256Sums
import io.github.devjardins.sidespeed.companion.update.verifyCompanionApk
import kotlinx.coroutines.CancellationException
import io.github.devjardins.sidespeed.companion.android.CompanionApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private const val UPDATE_DIR = "update"
private const val TAG = "SideSpeed"

/** Outcome of handing the verified APK to the system installer. */
sealed interface InstallResult {
    data object Started : InstallResult
    data class Failed(val reason: String?) : InstallResult
}

/**
 * "Update now" flow for the Android Companion app: downloads the release APK and checksums from
 * the fixed `latest/download` names, verifies them (SHA-256 against the checksums line, package,
 * signer equality with the installed app), and installs through a PackageInstaller session.
 * Files live in `cacheDir/update/` and are deleted after every outcome and on app start
 * ([clearStaleUpdateFiles]). Ported from the TV app's SelfUpdater verification, without the
 * helper path: this app installs directly through the system.
 */
class CompanionUpdater(
    private val appContext: Context,
    val installedVersion: String,
    private val download: CompanionDownload,
    private val readApkFacts: (File) -> ApkFacts?,
    private val installedSigners: () -> Set<String>,
    private val scope: CoroutineScope,
) {
    private val check = CompanionUpdateCheck(
        fetchJson = { download.fetchText(RELEASES_API_URL, COMPANION_UPDATE_CHECK_MAX_BYTES).getOrThrow() },
        installedVersion = installedVersion,
    )

    private val _state = MutableStateFlow<CompanionUpdateState>(CompanionUpdateState.Idle)
    val state: StateFlow<CompanionUpdateState> = _state.asStateFlow()

    private var lastTag: String? = null
    private var job: Job? = null
    private var installTimeout: Job? = null

    /** Manual check for the About screen: runs when it opens and when its button is pressed. */
    fun checkNow() {
        when (_state.value) {
            is CompanionUpdateState.Checking,
            is CompanionUpdateState.Downloading,
            is CompanionUpdateState.Installing,
            -> return
            else -> Unit
        }
        _state.value = CompanionUpdateState.Checking
        scope.launch {
            val result = check.checkNow()
            lastTag = (result as? CompanionUpdateState.Available)?.tag
            _state.value = result
        }
    }

    /** Starts the in-app update for the last available tag, unless one is already running. */
    @Synchronized
    fun startUpdate() {
        if (job?.isActive == true) return
        // The install itself keeps running after execute() returns (the system owns the
        // session), so the job guard above no longer covers it: stay put while Installing.
        if (_state.value is CompanionUpdateState.Installing) return
        job = scope.launch {
            try {
                execute()
            } catch (e: CancellationException) {
                deleteUpdateFiles()
                _state.value = lastTag?.let { CompanionUpdateState.Available(it) }
                    ?: CompanionUpdateState.Idle
                throw e
            } catch (e: Exception) {
                deleteUpdateFiles()
                _state.value = CompanionUpdateState.Failed("Update failed.")
            }
        }
    }

    /** Cancels a running download; the install itself cannot be cancelled once started. */
    fun cancel() {
        job?.cancel()
    }

    /** "Try again": resumes the update when a tag is known, otherwise re-checks first. */
    fun retry() {
        if (_state.value is CompanionUpdateState.Failed) {
            if (lastTag != null) startUpdate() else checkNow()
        }
    }

    /**
     * Reports the system installer's verdict for the committed session. Called by
     * [CompanionInstallStatusReceiver]; [status] is the [PackageInstaller.EXTRA_STATUS] value
     * and [message] the system's [PackageInstaller.EXTRA_STATUS_MESSAGE], if any. Late results
     * (after the timeout below already failed the update) are ignored. `lastTag` is kept, so
     * "Try again" restarts the update.
     */
    fun onInstallResult(status: Int, message: String?) {
        val next = installStatusToState(status, message) ?: return
        installTimeout?.cancel()
        installTimeout = null
        if (_state.value is CompanionUpdateState.Installing) {
            deleteUpdateFiles()
            _state.value = next
        }
    }

    private suspend fun execute() {
        lastTag ?: run {
            _state.value = CompanionUpdateState.Failed("Couldn't check for updates. Try again later.")
            return
        }
        if (Build.VERSION.SDK_INT >= 26) {
            val canRequest = try {
                appContext.packageManager.canRequestPackageInstalls()
            } catch (_: Exception) {
                true
            }
            if (!canRequest) {
                openUnknownSourcesSettings()
                _state.value = CompanionUpdateState.Failed(
                    "Allow SideSpeed Companion to install apps, then try again.",
                )
                return
            }
        }
        _state.value = CompanionUpdateState.Downloading(0)
        val sums = download.fetchText("$LATEST_DOWNLOAD_BASE_URL/$COMPANION_SUMS_NAME")
            .getOrElse { return fail("Could not download the checksums.") }
        val expected = parseSha256Sums(sums, COMPANION_APK_NAME)
            ?: return fail("No checksum found for $COMPANION_APK_NAME.")
        val dir = File(appContext.cacheDir, UPDATE_DIR).apply { mkdirs() }
        val apk = File(dir, COMPANION_APK_NAME)
        download.downloadTo("$LATEST_DOWNLOAD_BASE_URL/$COMPANION_APK_NAME", apk, onProgress = {
            _state.value = CompanionUpdateState.Downloading(it)
        }).getOrElse { return fail("Download failed.") }
        when (
            val verdict = verifyCompanionApk(sha256Hex(apk), expected, readApkFacts(apk), installedSigners())
        ) {
            is CompanionVerifyResult.Ok -> Unit
            is CompanionVerifyResult.Rejected -> return fail(verdict.reason)
        }
        _state.value = CompanionUpdateState.Installing
        when (val outcome = installViaSession(apk)) {
            // The session is committed; the system takes over from here. The state stays
            // Installing until CompanionInstallStatusReceiver reports the verdict (or the
            // timeout below fires), so "Update now" doesn't come back while the system
            // confirmation is up.
            is InstallResult.Started -> {
                deleteUpdateFiles()
                watchInstallTimeout()
            }
            is InstallResult.Failed -> return fail(outcome.reason ?: "Install failed.")
        }
    }

    private suspend fun fail(reason: String) {
        deleteUpdateFiles()
        _state.value = CompanionUpdateState.Failed(reason)
    }

    /**
     * Fails the update when the system never reports back: a committed session normally
     * confirms within seconds, so five silent minutes means the result was lost. Without this
     * the About screen would sit on Installing forever.
     */
    private fun watchInstallTimeout() {
        installTimeout?.cancel()
        installTimeout = scope.launch {
            delay(INSTALL_TIMEOUT_MS)
            if (_state.value is CompanionUpdateState.Installing) {
                deleteUpdateFiles()
                _state.value = CompanionUpdateState.Failed("Install didn't finish. Try again.")
            }
        }
    }

    private suspend fun installViaSession(apk: File): InstallResult = withContext(Dispatchers.IO) {
        try {
            val installer = appContext.packageManager.packageInstaller
            val sessionId = installer.createSession(
                PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL),
            )
            installer.openSession(sessionId).use { session ->
                apk.inputStream().use { input ->
                    session.openWrite("SideSpeed-Companion", 0, apk.length()).use { out ->
                        input.copyTo(out)
                        session.fsync(out)
                    }
                }
                // FLAG_MUTABLE on API 31+: commit() fills EXTRA_STATUS/EXTRA_INTENT into this
                // sender (immutable senders cannot be filled, and throw on targetSdk 35+).
                // The intent stays explicit to our own non-exported receiver, so nothing else
                // can intercept or forge it. FLAG_MUTABLE is an inlined constant, safe on older APIs.
                val status = Intent(appContext, CompanionInstallStatusReceiver::class.java).let {
                    PendingIntent.getBroadcast(
                        appContext, sessionId, it,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                    )
                }
                session.commit(status.intentSender)
            }
            InstallResult.Started
        } catch (e: Exception) {
            InstallResult.Failed(e.message)
        }
    }

    private fun openUnknownSourcesSettings() {
        try {
            appContext.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                    data = Uri.parse("package:${appContext.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                },
            )
        } catch (_: Exception) {
            // No settings screen (some rooted ROMs): the Failed line above still explains.
        }
    }

    private fun deleteUpdateFiles() {
        // Best effort: deleteRecursively throws AssertionError if a file vanishes mid-walk, which must not
        // crash the update coroutine.
        File(appContext.cacheDir, UPDATE_DIR).listFiles()?.forEach { runCatching { it.deleteRecursively() } }
    }
}

/** Deletes the update dir's contents; called on app start and after every update outcome. */
fun clearStaleUpdateFiles(context: Context) {
    File(context.cacheDir, UPDATE_DIR).listFiles()?.forEach { runCatching { it.deleteRecursively() } }
}

/** How long a committed session may stay silent before the update fails instead. */
internal const val INSTALL_TIMEOUT_MS = 5 * 60 * 1000L

/**
 * Maps a PackageInstaller session verdict to the next update state, or null when the verdict
 * needs no state change: the confirmation screen is launched by the receiver, and success
 * replaces the process, so neither touches the About screen. Pure for unit tests: [status]
 * is the `EXTRA_STATUS` value, [message] the `EXTRA_STATUS_MESSAGE`, if any.
 */
internal fun installStatusToState(status: Int, message: String?): CompanionUpdateState? = when (status) {
    PackageInstaller.STATUS_PENDING_USER_ACTION,
    PackageInstaller.STATUS_SUCCESS,
    -> null
    PackageInstaller.STATUS_FAILURE_ABORTED -> CompanionUpdateState.Failed("Update cancelled.")
    else -> if (message.isNullOrBlank()) {
        CompanionUpdateState.Failed("Install failed.")
    } else {
        CompanionUpdateState.Failed("Install failed. $message")
    }
}

/**
 * Receives the install session's status. The confirmation screen ([Intent.EXTRA_INTENT] on
 * [PackageInstaller.STATUS_PENDING_USER_ACTION]) is launched; success needs nothing (the
 * process is replaced); any other verdict is reported to the updater, which fails the update
 * but keeps `lastTag` so "Try again" restarts it.
 */
class CompanionInstallStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            val confirm: Intent? = if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_INTENT)
            }
            confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let { context.startActivity(it) }
            return
        }
        if (status == PackageInstaller.STATUS_SUCCESS) return
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        runCatching {
            (context.applicationContext as CompanionApplication).graph.updater.onInstallResult(status, message)
        }
    }
}

/** Lowercase hex of bytes, for certificate fingerprints. */
internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

/**
 * Picks the signer list to trust: [signingInfoSigners] when present and non-empty,
 * otherwise [legacySignatures]. Null when neither yields a signer, so callers fail closed.
 * On Android 9 `getPackageArchiveInfo` leaves `signingInfo` null for archives, so the
 * legacy signatures are the only source there; requesting both flags keeps them populated.
 */
internal fun <T> pickSigners(
    signingInfoSigners: List<T>?,
    legacySignatures: List<T>?,
): List<T>? {
    if (!signingInfoSigners.isNullOrEmpty()) return signingInfoSigners
    if (!legacySignatures.isNullOrEmpty()) return legacySignatures
    return null
}

/** SHA-256 hex set for a non-empty [signatures] list. */
internal fun signerSha256Set(signatures: List<Signature>): Set<String> {
    val md = java.security.MessageDigest.getInstance("SHA-256")
    return signatures.mapTo(mutableSetOf()) { md.digest(it.toByteArray()).toHex() }
}

/** The installed app's signing-certificate SHA-256 set, or empty when unreadable. */
fun installedSignerSha256(pm: PackageManager, packageName: String): Set<String> {
    return try {
        val flags = if (Build.VERSION.SDK_INT >= 28) {
            PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_SIGNATURES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }
        val info = pm.getPackageInfo(packageName, flags)
        val signatures = if (Build.VERSION.SDK_INT >= 28) {
            @Suppress("DEPRECATION")
            pickSigners(info.signingInfo?.apkContentsSigners?.toList(), info.signatures?.toList())
        } else {
            @Suppress("DEPRECATION")
            info.signatures?.toList()
        }
        signatures?.let { signerSha256Set(it) } ?: emptySet()
    } catch (e: Exception) {
        Log.w(TAG, "installedSignerSha256: could not read signers", e)
        emptySet()
    }
}

/**
 * Production [CompanionUpdater.readApkFacts]: package and signer set from the APK archive,
 * or null when the file is not a parseable APK.
 */
fun readApkFacts(pm: PackageManager, file: File): ApkFacts? {
    return try {
        val flags = if (Build.VERSION.SDK_INT >= 28) {
            PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_SIGNATURES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }
        val info = pm.getPackageArchiveInfo(file.absolutePath, flags)
        if (info == null) {
            Log.w(TAG, "readApkFacts: getPackageArchiveInfo returned null")
            return null
        }
        val packageName = info.packageName
        if (packageName == null) {
            Log.w(TAG, "readApkFacts: archive has no package name")
            return null
        }
        val signatures = if (Build.VERSION.SDK_INT >= 28) {
            @Suppress("DEPRECATION")
            pickSigners(info.signingInfo?.apkContentsSigners?.toList(), info.signatures?.toList())
        } else {
            @Suppress("DEPRECATION")
            info.signatures?.toList()
        }
        if (signatures == null) {
            Log.w(TAG, "readApkFacts: archive has no signers")
            return null
        }
        ApkFacts(packageName, signerSha256Set(signatures))
    } catch (e: Exception) {
        Log.w(TAG, "readApkFacts: failed to read archive", e)
        null
    }
}

/** Lowercase hex SHA-256 of a file's bytes. */
fun sha256Hex(file: File): String {
    val digest = java.security.MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            digest.update(buf, 0, n)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
