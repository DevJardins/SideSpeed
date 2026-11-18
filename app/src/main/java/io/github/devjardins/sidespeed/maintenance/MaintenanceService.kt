package io.github.devjardins.sidespeed.maintenance

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat
import io.github.devjardins.sidespeed.SideSpeedApp
import io.github.devjardins.sidespeed.tools.CleanupThreshold
import io.github.devjardins.sidespeed.tools.shortLabel
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.hours

/**
 * Notification text for the current engine state. [armed] is compile maintenance (a ticked app or a
 * covering mode); cleanup-only runs show the threshold. When both are on, the compile text wins.
 */
fun notificationText(state: MaintenanceState, armed: Boolean, threshold: CleanupThreshold): String = when {
    state.helperDown && state.queueLength > 0 -> "Waiting for the helper"
    state.queueLength > 0 -> "Optimizing apps (${state.queueLength} left)"
    armed && state.maintained.isEmpty() -> "Watching for apps to optimize"
    armed && state.maintained.size == 1 -> "Keeping 1 app optimized"
    armed -> "Keeping ${state.maintained.size} apps optimized"
    threshold == CleanupThreshold.OFF -> "Watching for apps to optimize"
    else -> "Keeping free space above ${threshold.shortLabel()}"
}

/**
 * Foreground type for the service. specialUse (API 34+) has no 6-hour timeout and may start at boot;
 * dataSync is the nearest fit before that; none before API 29.
 */
fun foregroundServiceType(sdk: Int): Int? = when {
    sdk >= 34 -> ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
    sdk >= 29 -> ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
    else -> null
}

/**
 * Keeps the process alive and owns the triggers: start, package added/replaced, and a 6-hourly rescan
 * (which also runs one automatic-cleanup pass). Set changes are watched by the engine itself. Runs only
 * while [isServiceNeeded]; stops itself otherwise.
 */
class MaintenanceService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, _ -> })
    private var started = false
    private var receiver: BroadcastReceiver? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (started) return START_STICKY
        started = true
        val container = (application as SideSpeedApp).container
        val engine = container.maintenance
        try {
            val initial = engine.state.value
            promote(notification(notificationText(initial, initial.maintained.isNotEmpty(), CleanupThreshold.OFF)))
        } catch (_: Exception) {
            // The platform refused the foreground start (boot or background restrictions): run one
            // engine + cleanup pass without the loop, then stop.
            engine.scan()
            scope.launch {
                runCatching { container.cleanup.maybeAuto() }
                stopSelf()
            }
            return START_NOT_STICKY
        }

        receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val target = packageEventTarget(
                    intent.action, intent.data?.schemeSpecificPart, packageName,
                    intent.getBooleanExtra(Intent.EXTRA_REPLACING, false),
                )
                if (target != null) engine.scan()
            }
        }.also {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_REPLACED)
                addDataScheme("package")
            }
            ContextCompat.registerReceiver(this, it, filter, ContextCompat.RECEIVER_EXPORTED)
        }

        scope.launch {
            combine(
                engine.state,
                container.maintenanceStore.maintained,
                container.maintenanceStore.mode,
                container.toolsStore.cleanupThreshold,
                container.launchRunner.measuring,
            ) { s, t, m, th, meas ->
                val armed = isArmed(m, t)
                val needed = isServiceNeeded(m, t, th, measuring = meas != null)
                needed to if (needed) notificationText(s, armed, th) else null
            }.collect { (needed, text) ->
                if (!needed || text == null) {
                    stopSelf()
                } else {
                    getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))
                }
            }
        }
        scope.launch {
            val cleanup = container.cleanup
            val launches = container.launchRunner
            while (true) {
                engine.scan()
                runCatching { cleanup.maybeAuto() }
                runCatching { launches.restorePending() }
                delay(6.hours)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        receiver?.let { unregisterReceiver(it) }
        scope.cancel()
        super.onDestroy()
    }

    private fun promote(n: Notification) {
        val type = foregroundServiceType(Build.VERSION.SDK_INT)
        // The 3-arg overload needs API 29; type is non-null only from API 29 up, spelled out for lint.
        if (Build.VERSION.SDK_INT >= 29 && type != null) startForeground(NOTIFICATION_ID, n, type)
        else startForeground(NOTIFICATION_ID, n)
    }

    private fun notification(text: String): Notification {
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Keeping apps optimized", NotificationManager.IMPORTANCE_MIN),
            )
        }
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL_ID) else @Suppress("DEPRECATION") Notification.Builder(this)
        return builder
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("SideSpeed")
            .setContentText(text)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "maintenance"
        private const val NOTIFICATION_ID = 1

        /** Starts the foreground service; if the platform refuses (background start), the engine still runs in-process. */
        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, MaintenanceService::class.java))
            } catch (_: Exception) {
                (context.applicationContext as SideSpeedApp).container.maintenance.scan()
            }
        }
    }
}
