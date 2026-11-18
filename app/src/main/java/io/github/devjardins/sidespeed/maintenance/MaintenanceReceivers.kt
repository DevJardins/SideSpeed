package io.github.devjardins.sidespeed.maintenance

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.devjardins.sidespeed.SideSpeedApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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

/** Restarts the service after boot, but only for users who turned a feature on. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val container = (context.applicationContext as SideSpeedApp).container
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                if (isServiceNeeded(container.maintenanceStore, container.toolsStore)) MaintenanceService.start(context.applicationContext)
            } finally {
                pending.finish()
            }
        }
    }
}
