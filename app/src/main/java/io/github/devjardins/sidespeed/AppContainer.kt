package io.github.devjardins.sidespeed

import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.os.Build
import io.github.devjardins.sidespeed.adb.AdbBackend
import io.github.devjardins.sidespeed.adb.ConnectionStrategy
import io.github.devjardins.sidespeed.adb.HelperProbe
import io.github.devjardins.sidespeed.adb.LibAdbBackend
import io.github.devjardins.sidespeed.data.DataStorePrefs
import io.github.devjardins.sidespeed.data.Prefs
import io.github.devjardins.sidespeed.helper.HelperClient
import io.github.devjardins.sidespeed.apps.AppIcons
import io.github.devjardins.sidespeed.apps.InstalledApps
import io.github.devjardins.sidespeed.ui.setup.SettingsReader
import io.github.devjardins.sidespeed.compile.CompileRunner
import io.github.devjardins.sidespeed.status.StatusChecker
import io.github.devjardins.sidespeed.maintenance.DataStoreMaintenanceStore
import io.github.devjardins.sidespeed.maintenance.MaintenanceEngine
import io.github.devjardins.sidespeed.maintenance.MaintenanceStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.net.Inet4Address

class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val adb: AdbBackend = LibAdbBackend(appContext, appContext.filesDir)

    /** The single shared helper client: the helper writes one ack file, so two clients could fail each other's nonce check. */
    val helper: HelperClient = HelperClient()

    val prefs: Prefs = DataStorePrefs(appContext)

    val installedApps = InstalledApps(appContext)

    val appIcons = AppIcons(appContext)

    val settings: SettingsReader = SettingsReader.of(appContext.contentResolver)

    /** Only use after Setup reports the helper ready. */
    val compileRunner: CompileRunner by lazy { CompileRunner(helper) }
    val statusChecker: StatusChecker by lazy { StatusChecker(helper) }

    val maintenanceStore: MaintenanceStore by lazy { DataStoreMaintenanceStore(appContext) }

    /** Keeps maintained apps compiled; created on first use, which starts its scanning. */
    val maintenance: MaintenanceEngine by lazy {
        MaintenanceEngine(
            source = installedApps,
            store = maintenanceStore,
            prefs = prefs,
            runCompile = { pkg, mode -> compileRunner.run(pkg, mode) },
            helperUp = { helper.ping() != null },
            badges = { statusChecker.badges(it) },
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + kotlinx.coroutines.CoroutineExceptionHandler { _, _ -> }),
            ownPackage = appContext.packageName,
        )
    }

    val isFireOs: Boolean = Build.MANUFACTURER.equals("Amazon", ignoreCase = true) ||
        appContext.packageManager.hasSystemFeature("amazon.hardware.fire_tv")

    val strategy: ConnectionStrategy = ConnectionStrategy(
        helper = HelperProbe { helper.ping() },
        adb = adb,
        prefs = prefs,
        expectedApk = appContext.applicationInfo.sourceDir,
        isFireOs = isFireOs,
    )

    fun wifiIp(): String? = try {
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        cm.activeNetwork?.let { cm.getLinkProperties(it) }?.linkAddresses
            ?.map { it.address }?.filterIsInstance<Inet4Address>()?.firstOrNull()?.hostAddress
    } catch (_: Exception) {
        null
    }
}
