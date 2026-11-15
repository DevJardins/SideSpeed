package io.github.devjardins.sidespeed.apps

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.Build

class InstalledApps(context: Context) {
    private val ownPackage = context.packageName
    private val pm: PackageManager = context.packageManager

    /** Launchable apps (LEANBACK_LAUNCHER or LAUNCHER), de-duplicated by package, excluding SideSpeed itself. */
    fun list(): List<AppEntry> =
        listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)
            .flatMap { queryLaunchable(it) }
            .distinctBy { it.activityInfo.packageName }
            .filter { it.activityInfo.packageName != ownPackage }
            .mapNotNull { ri ->
                val pkg = ri.activityInfo.packageName
                packageInfo(pkg)?.let { entry(pkg, ri.loadLabel(pm).toString(), it) }
            }

    /**
     * Any installed package, launchable or not, so manual entry can accept packages
     * without a launcher activity. The label falls back to the package name.
     * Null if the package is not installed.
     */
    fun find(pkg: String): AppEntry? {
        val info = packageInfo(pkg) ?: return null
        val label = info.applicationInfo?.loadLabel(pm)?.toString()
        return entry(pkg, label, info)
    }

    private fun entry(pkg: String, label: String?, info: PackageInfo) =
        AppEntry(pkg, label?.takeIf { it.isNotBlank() } ?: pkg, installerOf(pkg), info.lastUpdateTime)

    private fun queryLaunchable(category: String): List<ResolveInfo> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(category)
        return if (Build.VERSION.SDK_INT >= 33) {
            pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(intent, 0)
        }
    }

    private fun packageInfo(pkg: String): PackageInfo? = try {
        if (Build.VERSION.SDK_INT >= 33) {
            pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(pkg, 0)
        }
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }

    private fun installerOf(pkg: String): String? = try {
        if (Build.VERSION.SDK_INT >= 30) {
            pm.getInstallSourceInfo(pkg).installingPackageName
        } else {
            @Suppress("DEPRECATION")
            pm.getInstallerPackageName(pkg)
        }
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }
}
