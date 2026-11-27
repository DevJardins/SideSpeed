package io.github.devjardins.sidespeed.companion.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.view.View
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import io.github.devjardins.sidespeed.companion.resources.Res
import io.github.devjardins.sidespeed.companion.resources.notif_install_committing
import io.github.devjardins.sidespeed.companion.resources.notif_install_uploading
import io.github.devjardins.sidespeed.companion.ui.InstallTransferHost
import io.github.devjardins.sidespeed.companion.ui.text.UiText
import io.github.devjardins.sidespeed.companion.ui.text.resolveSuspend
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** What the ongoing notification shows. [percent] is -1 when the total is not known. */
data class InstallNotificationSnapshot(
    val tvName: String,
    val appName: String,
    val bytesSent: Long,
    val totalBytes: Long,
    val committing: Boolean,
    val percent: Int,
)

/**
 * The notification's state. Progress arrives from an IO thread and may trail a cancel, so every call is
 * synchronized and progress after [end] is dropped. Plain progress updates are throttled; a change to committing is not.
 */
class InstallNotificationModel(private val throttleMs: Long = 250, private val clock: () -> Long) {
    private var snapshot: InstallNotificationSnapshot? = null
    private var lastEmitAt = Long.MIN_VALUE

    @Synchronized
    fun start(tvName: String, appName: String) {
        snapshot = InstallNotificationSnapshot(tvName, appName, 0, 0, committing = false, percent = -1)
        lastEmitAt = Long.MIN_VALUE
    }

    /** The snapshot to show now, or null when nothing started or the update is throttled. */
    @Synchronized
    fun progress(bytesSent: Long, totalBytes: Long, committing: Boolean): InstallNotificationSnapshot? {
        val cur = snapshot ?: return null
        val now = clock()
        val phaseChanged = committing != cur.committing
        val next = cur.copy(
            bytesSent = bytesSent,
            totalBytes = totalBytes,
            committing = committing,
            percent = if (totalBytes > 0) (bytesSent * 100 / totalBytes).coerceIn(0, 100).toInt() else -1,
        )
        snapshot = next
        if (!phaseChanged && lastEmitAt != Long.MIN_VALUE && now - lastEmitAt < throttleMs) return null
        lastEmitAt = now
        return next
    }

    @Synchronized
    fun current(): InstallNotificationSnapshot? = snapshot

    @Synchronized
    fun end() {
        snapshot = null
    }
}

/** Shared between the host (called from the install's IO thread) and the service. */
internal object InstallTransferState {
    val model = InstallNotificationModel { System.currentTimeMillis() }
}

/**
 * Keeps the process in the foreground while a remote install transfers, so a long upload survives the app
 * going to the background. Started and stopped by [AndroidInstallTransferHost].
 */
class InstallTransferService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannel()
        // Must be called promptly after startForegroundService: show a plain notice first, then the real text.
        val first = InstallTransferState.model.current()
        val placeholder = build(first, first?.let { "${it.appName} - ${it.tvName}" } ?: getString(R.string.app_name))
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, placeholder, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, placeholder)
        }
        if (first == null) {
            // The install ended before the service came up.
            stopSelf()
            return START_NOT_STICKY
        }
        refresh(first)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        scope.cancel()
        super.onDestroy()
    }

    /** Resolves the localized text off the caller's thread, then updates the notification. */
    internal fun refresh(snapshot: InstallNotificationSnapshot) {
        scope.launch {
            val rtl = resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL
            val args = listOf(UiText.Fsi(snapshot.appName), UiText.Fsi(snapshot.tvName))
            val text = UiText.Res(
                if (snapshot.committing) Res.string.notif_install_committing else Res.string.notif_install_uploading,
                args,
            ).resolveSuspend(rtl)
            // Ended while the text was being resolved: do not repost over a cancelled notification.
            if (InstallTransferState.model.current() == null) return@launch
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, build(snapshot, text))
        }
    }

    private fun build(snapshot: InstallNotificationSnapshot?, text: String): Notification {
        val b = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
        if (snapshot != null) {
            val indeterminate = snapshot.committing || snapshot.percent < 0
            b.setProgress(100, snapshot.percent.coerceAtLeast(0), indeterminate)
        }
        return b.build()
    }

    private fun ensureChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.channel_installs), NotificationManager.IMPORTANCE_LOW),
        )
    }

    companion object {
        const val CHANNEL_ID = "installs"
        private const val NOTIFICATION_ID = 0x5D01

        @Volatile
        internal var instance: InstallTransferService? = null
    }
}

/** Bridges the view model's install progress to [InstallTransferService]. Safe to call from any thread. */
class AndroidInstallTransferHost(private val context: Context) : InstallTransferHost {
    private val model = InstallTransferState.model

    override fun onStart(tvName: String, appName: String) {
        model.start(tvName, appName)
        // Refused when the app cannot start a foreground service right now; the install itself still runs.
        runCatching { ContextCompat.startForegroundService(context, Intent(context, InstallTransferService::class.java)) }
    }

    override fun onProgress(bytesSent: Long, totalBytes: Long, committing: Boolean) {
        val snapshot = model.progress(bytesSent, totalBytes, committing) ?: return
        InstallTransferService.instance?.refresh(snapshot)
    }

    override fun onEnd() {
        model.end()
        runCatching { context.stopService(Intent(context, InstallTransferService::class.java)) }
    }
}
