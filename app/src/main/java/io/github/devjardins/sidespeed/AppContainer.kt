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
import io.github.devjardins.sidespeed.status.TvAddress
import io.github.devjardins.sidespeed.net.LanCandidate
import io.github.devjardins.sidespeed.net.pickLanIpv4
import android.net.Network
import android.net.NetworkCapabilities
import java.net.Inet4Address
import java.net.NetworkInterface

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

    /** This TV's own showable addresses (LAN first, then Tailscale), or empty when unknown. */
    fun tvAddresses(): List<String> = try {
        val ifs = NetworkInterface.getNetworkInterfaces() ?: return emptyList()
        TvAddress.pick(
            ifs.asSequence().filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.asSequence() }.filterIsInstance<Inet4Address>()
                .mapNotNull { it.hostAddress }.toList(),
        )
    } catch (_: Exception) {
        emptyList()
    }

    fun wifiIp(): String? = try {
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val active = cm.activeNetwork?.let { candidate(cm, it) }
        @Suppress("DEPRECATION") // allNetworks is the only synchronous list; a callback-tracked set would add state.
        val all = cm.allNetworks.mapNotNull { candidate(cm, it) }
        pickLanIpv4(active, all)
    } catch (_: Exception) {
        null
    }

    /** Null when the network vanished between calls (no capabilities). */
    private fun candidate(cm: ConnectivityManager, network: Network): LanCandidate? {
        val caps = cm.getNetworkCapabilities(network) ?: return null
        return LanCandidate(
            isVpn = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN),
            isWifiOrEthernet = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET),
            ipv4 = cm.getLinkProperties(network)?.linkAddresses
                ?.map { it.address }?.filterIsInstance<Inet4Address>()?.firstOrNull()?.hostAddress,
        )
    }
}
