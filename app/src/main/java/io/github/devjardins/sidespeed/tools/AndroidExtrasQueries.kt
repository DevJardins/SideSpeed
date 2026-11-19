package io.github.devjardins.sidespeed.tools

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings

class AndroidExtrasQueries(context: Context, private val ownPackage: String) : ExtrasQueries {
    private val pm: PackageManager = context.packageManager

    override fun labelOf(pkg: String): String? = try {
        val info = pm.getApplicationInfo(pkg, 0)
        pm.getApplicationLabel(info).toString().takeIf { it.isNotBlank() }
    } catch (_: Exception) {
        null
    }

    override fun isInstalled(pkg: String): Boolean = try {
        pm.getApplicationInfo(pkg, 0)
        true
    } catch (_: Exception) {
        false
    }

    override fun settingsHandlerPackages(): Set<String> = run {
        val intent = Intent(Settings.ACTION_SETTINGS)
        queryPackages(intent)
    }

    override fun tvInputHardwarePackages(): Set<String> {
        val out = mutableSetOf<String>()
        try {
            val packages = if (Build.VERSION.SDK_INT >= 33) {
                pm.getInstalledPackages(
                    PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()),
                )
            } else {
                @Suppress("DEPRECATION")
                pm.getInstalledPackages(PackageManager.GET_PERMISSIONS)
            }
            for (info in packages) {
                val perms = info.requestedPermissions ?: continue
                if ("android.permission.TV_INPUT_HARDWARE" in perms) out += info.packageName
            }
        } catch (_: Exception) {
        }
        return out
    }

    private fun queryPackages(intent: Intent): Set<String> = try {
        val matches = if (Build.VERSION.SDK_INT >= 33) {
            pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(intent, 0)
        }
        matches.mapNotNull { it.activityInfo?.packageName }.toSet()
    } catch (_: Exception) {
        emptySet()
    }
}