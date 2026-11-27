package io.github.devjardins.sidespeed.maintenance

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.devjardins.sidespeed.SideSpeedApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The package to rescan for a package broadcast, or null to ignore it. SideSpeed itself is ignored, and so is
 * `PACKAGE_ADDED` with `EXTRA_REPLACING`: `PACKAGE_REPLACED` follows for the same update.
 */
fun packageEventTarget(action: String?, dataSchemeSpecificPart: String?, ownPackage: String, replacing: Boolean): String? {
    val pkg = dataSchemeSpecificPart?.takeIf { it.isNotBlank() && it != ownPackage } ?: return null
    return when (action) {
        Intent.ACTION_PACKAGE_REPLACED -> pkg
        Intent.ACTION_PACKAGE_ADDED -> pkg.takeUnless { replacing }
        else -> null
    }
}

internal suspend fun handleBootCompleted(
    serviceNeeded: Boolean,
    isFireOs: Boolean,
    rebootRecoveryEnabled: Boolean,
    startMaintenance: () -> Unit,
    scheduleBoot: () -> Unit,
    scheduleDaily: () -> Unit,
) {
    if (serviceNeeded) startMaintenance()
    if (!isFireOs && rebootRecoveryEnabled) {
        scheduleBoot()
        scheduleDaily()
    }
}

/** Restarts the service after boot, and schedules reboot recovery when turned on. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val appContext = context.applicationContext
        val container = (appContext as SideSpeedApp).container
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                handleBootCompleted(
                    serviceNeeded = isServiceNeeded(container.maintenanceStore, container.toolsStore),
                    isFireOs = container.isFireOs,
                    rebootRecoveryEnabled = container.rebootRecoveryStore.enabled.first(),
                    startMaintenance = { MaintenanceService.start(appContext) },
                    scheduleBoot = { RebootRecoveryJob.scheduleBoot(appContext) },
                    scheduleDaily = { RebootRecoveryJob.scheduleDaily(appContext) },
                )
            } finally {
                pending.finish()
            }
        }
    }
}
