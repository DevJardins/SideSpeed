package io.github.devjardins.sidespeed.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.annotation.StringRes
import io.github.devjardins.sidespeed.R
import io.github.devjardins.sidespeed.helper.InstallOutcome
import io.github.devjardins.sidespeed.ui.text.UiText
import io.github.devjardins.sidespeed.ui.text.resolve
import io.github.devjardins.sidespeed.ui.text.uiText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** The fallback install decision: open the unknown-sources settings, or install directly. */
enum class FallbackAction { OpenSettings, Install }

/** Pre-26 there is no per-app unknown-sources gate; on 26+ it applies only when not yet granted. */
fun fallbackAction(sdk: Int, canRequest: Boolean): FallbackAction =
    if (sdk >= 26 && !canRequest) FallbackAction.OpenSettings else FallbackAction.Install

/**
 * The unknown-sources prompt, naming the flow's own retry button ("Retry" for
 * self-updates, "Try again" for app updates), so the words match the button below.
 */
fun unknownSourcesMessage(retryLabel: UiText): UiText =
    uiText(R.string.update_unknown_sources, retryLabel)

/**
 * System-installer fallback for when the helper is down: hands the verified APK to a
 * PackageInstaller session. Android shows its own confirmation, so [InstallOutcome.Started]
 * means the session was committed, not that the install finished.
 */
class FallbackInstaller(
    private val context: Context,
    @StringRes private val retryLabel: Int = R.string.update_banner_retry,
) {
    suspend fun install(apk: File): InstallOutcome = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val canRequest = if (Build.VERSION.SDK_INT >= 26) pm.canRequestPackageInstalls() else true
        if (fallbackAction(Build.VERSION.SDK_INT, canRequest) == FallbackAction.OpenSettings) {
            return@withContext try {
                context.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                        data = Uri.parse("package:${context.packageName}")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    },
                )
                InstallOutcome.Failed(0, unknownSourcesMessage(UiText.Res(retryLabel)).resolve(context))
            } catch (_: Exception) {
                // No settings screen (some rooted ROMs): only the Downloader caption remains.
                InstallOutcome.Failed(0, null)
            }
        }
        try {
            val installer = pm.packageInstaller
            val sessionId = installer.createSession(
                PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL),
            )
            installer.openSession(sessionId).use { session ->
                apk.inputStream().use { input ->
                    session.openWrite("SideSpeed", 0, apk.length()).use { out ->
                        input.copyTo(out)
                        session.fsync(out)
                    }
                }
                // FLAG_MUTABLE on API 31+: commit() fills EXTRA_STATUS/EXTRA_INTENT into this
                // sender (immutable senders cannot be filled, and throw on targetSdk 35+).
                // The intent stays explicit to our own non-exported receiver, so nothing else
                // can intercept or forge it. FLAG_MUTABLE is an inlined constant, safe on older APIs.
                val status = Intent(context, InstallStatusReceiver::class.java).let {
                    PendingIntent.getBroadcast(
                        context, sessionId, it,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                    )
                }
                session.commit(status.intentSender)
            }
            InstallOutcome.Started
        } catch (e: Exception) {
            InstallOutcome.Failed(0, e.message)
        }
    }
}

/**
 * Receives the fallback session's status. The confirmation screen ([Intent.EXTRA_INTENT] on
 * [PackageInstaller.STATUS_PENDING_USER_ACTION]) is the only status acted on; every other
 * outcome needs no in-app handling — success replaces the app, failure leaves it running.
 */
class InstallStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1) != PackageInstaller.STATUS_PENDING_USER_ACTION) return
        val confirm: Intent? = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_INTENT)
        }
        confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let { context.startActivity(it) }
    }
}
