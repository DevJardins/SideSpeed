package io.github.devjardins.sidespeed.apps

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.Build
import io.github.devjardins.sidespeed.compile.ActivityClassName

/** Installed version identity used to match a stored launch-time result. */
data class PkgVersion(val versionCode: Long, val lastUpdateTime: Long)

class InstalledApps(context: Context) : AppSource {
    private val ownPackage = context.packageName
    private val pm: PackageManager = context.packageManager

    /** Launchable apps (LEANBACK_LAUNCHER or LAUNCHER), de-duplicated by package, excluding SideSpeed itself. */
    override fun list(): List<AppEntry> =
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
    override fun find(pkg: String): AppEntry? {
        val info = packageInfo(pkg) ?: return null
        val label = info.applicationInfo?.loadLabel(pm)?.toString()
        return entry(pkg, label, info)
    }

    private fun entry(pkg: String, label: String?, info: PackageInfo) =
        AppEntry(
            pkg,
            label?.takeIf { it.isNotBlank() } ?: pkg,
            installerOf(pkg),
            info.lastUpdateTime,
            isSystem = isSystem(info),
        )

    private fun isSystem(info: PackageInfo): Boolean {
        val flags = info.applicationInfo?.flags ?: return false
        return (flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0
    }

    private fun queryLaunchable(category: String): List<ResolveInfo> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(category)
        return query(intent)
    }

    private fun query(intent: Intent): List<ResolveInfo> =
        if (Build.VERSION.SDK_INT >= 33) {
            pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(intent, 0)
        }

    /** Installed version identity for launch-result matching, or null when the package is gone. */
    fun versionOf(pkg: String): PkgVersion? {
        val info = packageInfo(pkg) ?: return null
        val versionCode = if (Build.VERSION.SDK_INT >= 28) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
        return PkgVersion(versionCode, info.lastUpdateTime)
    }

    /**
     * Fully-qualified launch activity class for [pkg], leanback first then
     * normal launcher, or null when SideSpeed cannot open the app. Short names
     * (`".Main"`, `"Main"`) are resolved against the package; anything that is
     * not a valid binary class name ([ActivityClassName], including nested
     * `Outer$Inner` activities) is rejected so the helper allowlist holds.
     */
    fun launchActivity(pkg: String): String? {
        for (category in listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)) {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(category).setPackage(pkg)
            val matches = try {
                query(intent)
            } catch (_: Exception) {
                continue
            }
            val name = matches.firstOrNull { it.activityInfo?.packageName == pkg }?.activityInfo?.name
                ?: continue
            val qualified = when {
                name.startsWith(".") -> pkg + name
                "." !in name -> "$pkg.$name"
                else -> name
            }
            ActivityClassName.parse(qualified)?.let { return it.value }
        }
        return null
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
