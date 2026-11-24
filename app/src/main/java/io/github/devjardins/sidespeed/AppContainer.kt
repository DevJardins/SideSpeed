package io.github.devjardins.sidespeed

import android.content.Context
import android.content.pm.ApplicationInfo
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
import io.github.devjardins.sidespeed.BuildConfig
import io.github.devjardins.sidespeed.tools.AndroidDeviceInfo
import io.github.devjardins.sidespeed.tools.AndroidExtrasQueries
import io.github.devjardins.sidespeed.tools.Extras
import io.github.devjardins.sidespeed.tools.loadExtrasIndex
import io.github.devjardins.sidespeed.tools.loadExtrasList
import io.github.devjardins.sidespeed.tools.Cleanup
import io.github.devjardins.sidespeed.tools.DataStoreToolsStore
import io.github.devjardins.sidespeed.tools.DeviceInfo
import io.github.devjardins.sidespeed.tools.LaunchRunner
import io.github.devjardins.sidespeed.maintenance.JobPhase
import io.github.devjardins.sidespeed.update.FallbackInstaller
import io.github.devjardins.sidespeed.tools.ToolsCommands
import io.github.devjardins.sidespeed.tools.ToolsStore
import io.github.devjardins.sidespeed.update.SelfUpdater
import io.github.devjardins.sidespeed.update.UpdateDownload
import io.github.devjardins.sidespeed.update.installedSignerSha256
import io.github.devjardins.sidespeed.update.readApkFacts
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import io.github.devjardins.sidespeed.ui.setup.SettingsReader
import io.github.devjardins.sidespeed.compile.CompileRunner
import io.github.devjardins.sidespeed.status.StatusChecker
import io.github.devjardins.sidespeed.maintenance.DataStoreMaintenanceStore
import io.github.devjardins.sidespeed.maintenance.MaintenanceEngine
import io.github.devjardins.sidespeed.maintenance.MaintenanceService
import io.github.devjardins.sidespeed.maintenance.MaintenanceStore
import io.github.devjardins.sidespeed.maintenance.SelfCompiler
import io.github.devjardins.sidespeed.status.CompileStatus
import io.github.devjardins.sidespeed.status.DataStoreCompileStatusStore
import io.github.devjardins.sidespeed.ui.text.stringLookup
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import io.github.devjardins.sidespeed.status.TvAddress
import io.github.devjardins.sidespeed.updates.AppUpdateCheck
import io.github.devjardins.sidespeed.updates.AppUpdateStore
import io.github.devjardins.sidespeed.updates.AppUpdater
import io.github.devjardins.sidespeed.updates.DataStoreAppUpdateStore
import io.github.devjardins.sidespeed.updates.UpdateEntry
import io.github.devjardins.sidespeed.updates.httpAppUpdateFetch
import io.github.devjardins.sidespeed.updates.parseAppUpdates
import io.github.devjardins.sidespeed.net.LanCandidate
import kotlinx.coroutines.flow.map
import io.github.devjardins.sidespeed.net.pickLanIpv4
import android.net.Network
import android.net.NetworkCapabilities
import java.net.Inet4Address
import java.net.NetworkInterface

/** SideSpeed's own install identity, read once from the PackageManager. */
data class SelfBuild(val versionCode: Long, val lastUpdateTime: Long, val debuggable: Boolean, val versionName: String)

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

    val toolsStore: ToolsStore by lazy { DataStoreToolsStore(appContext) }

    /** Only use after Setup reports the helper ready. */
    val toolsCommands: ToolsCommands by lazy { ToolsCommands(helper) { helper.ping() != null } }

    /** Free-up-space actions; automatic cleanup runs from [MaintenanceService]. */
    val cleanup: Cleanup by lazy { Cleanup(toolsCommands, deviceInfo, toolsStore) }

    /**
     * True while the TV is busy with something an update must not interrupt: a compile, a
     * launch-time measurement, a maintenance job, or a cleanup run. Gates the banner's Update now.
     */
    val updateBusy: Flow<Boolean> by lazy {
        combine(
            compileStatus.active,
            launchRunner.measuring,
            maintenance.state,
            cleanup.running,
        ) { compiling, measuring, maintenanceState, cleaning ->
            compiling || measuring != null ||
                maintenanceState.phases.values.any { it == JobPhase.RUNNING } ||
                cleaning
        }
    }

    /** In-app self-update: silently through the helper, or the system installer when it is down. */
    val selfUpdater: SelfUpdater by lazy {
        val fallback = FallbackInstaller(appContext)
        SelfUpdater(
            download = UpdateDownload(UpdateDownload::httpOpen),
            readApkFacts = { readApkFacts(appContext.packageManager, it) },
            installed = {
                selfBuild.versionCode to installedSignerSha256(appContext.packageManager, appContext.packageName)
            },
            helperUp = { helper.ping() != null },
            helperInstall = { helper.installSelf(it) },
            fallbackInstall = { fallback.install(it) },
            cacheDir = appContext.cacheDir,
            busy = updateBusy,
        )
    }

    /** Sideloaded-app update catalog: notify-only state, refreshed at most once per 24 h. */
    val appUpdateStore: AppUpdateStore by lazy { DataStoreAppUpdateStore(appContext) }

    /** Fetches updates-v2.json with the stored ETag; failures stay silent and keep the last good copy. */
    val appUpdateCheck: AppUpdateCheck by lazy {
        AppUpdateCheck(httpAppUpdateFetch(), appUpdateStore, System::currentTimeMillis)
    }

    /**
     * Per-app update flow ("Update now" on the Compile screen): downloads the picked variant and
     * installs it through the helper, or the system installer when the helper is down.
     */
    val appUpdater: AppUpdater by lazy {
        // The Compile screen's retry button says "Try again", so the fallback names that.
        val fallback = FallbackInstaller(appContext, R.string.compile_update_try_again)
        AppUpdater(
            download = UpdateDownload(UpdateDownload::httpOpen),
            readApkFacts = { readApkFacts(appContext.packageManager, it) },
            installed = { pkg ->
                val pm = appContext.packageManager
                val info = if (Build.VERSION.SDK_INT >= 33) {
                    pm.getPackageInfo(
                        pkg,
                        PackageManager.PackageInfoFlags.of(0),
                    )
                } else {
                    @Suppress("DEPRECATION")
                    pm.getPackageInfo(pkg, 0)
                }
                val code = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else {
                    @Suppress("DEPRECATION")
                    info.versionCode.toLong()
                }
                code to installedSignerSha256(pm, pkg)
            },
            helperUp = { helper.ping() != null },
            helperInstall = { pkg, apk -> helper.installUpdate(pkg, apk) },
            fallbackInstall = { fallback.install(it) },
            cacheDir = appContext.cacheDir,
        )
    }

    /**
     * The last good catalog, parsed for badges; empty until the first successful fetch.
     * Parsed once per new body on a background thread, filtered to installed
     * packages so the in-memory map stays small, and shared so every collector
     * (Home, My Apps, Compile) sees the same parse.
     */
    val appUpdateCatalog: Flow<Map<String, UpdateEntry>> by lazy {
        appUpdateStore.json
            .map { raw ->
                if (raw == null) return@map emptyMap<String, UpdateEntry>()
                val installed = runCatching {
                    installedApps.list().map { it.packageName }.toSet()
                }.getOrDefault(emptySet())
                parseAppUpdates(raw, installed) ?: emptyMap()
            }
            .flowOn(Dispatchers.Default)
            .stateIn(appScope, SharingStarted.Eagerly, emptyMap())
    }

    /** Home screen switcher: in-app PackageManager reads, helper writes. */
    val homeSwitcher: io.github.devjardins.sidespeed.tools.HomeSwitcher by lazy {
        io.github.devjardins.sidespeed.tools.HomeSwitcher(
            queries = io.github.devjardins.sidespeed.tools.AndroidHomeQueries(appContext),
            commands = toolsCommands,
            store = toolsStore,
            ownPackage = appContext.packageName,
            isFireOs = isFireOs,
            sideSpeedDisabled = {
                toolsStore.extrasDisabled.first().map { it.pkg }.toSet()
            },
        )
    }

    fun extras(forcedListId: String? = null): Extras {
        val index = loadExtrasIndex(appContext)
        val forced = forcedListId?.takeIf { BuildConfig.DEBUG }
        return Extras(
            index = index,
            loadEntries = { loadExtrasList(appContext, it.file) },
            queries = AndroidExtrasQueries(appContext, appContext.packageName),
            commands = toolsCommands,
            store = toolsStore,
            homeCurrent = { homeSwitcher.currentWithFallback() },
            ownPackage = appContext.packageName,
            forcedFolderId = forced,
        )
    }

    fun extrasIndex() = loadExtrasIndex(appContext)

    val deviceInfo: DeviceInfo by lazy { AndroidDeviceInfo(appContext) }
    val statusChecker: StatusChecker by lazy { StatusChecker(helper) }

    val maintenanceStore: MaintenanceStore by lazy { DataStoreMaintenanceStore(appContext) }

    /** Container-level scope: outlives screens and ViewModels, so launch sequences survive them. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default + kotlinx.coroutines.CoroutineExceptionHandler { _, _ -> })

    /** What SideSpeed is compiling right now, for the shell-only status provider. */
    val compileStatus: CompileStatus by lazy {
        CompileStatus(
            store = DataStoreCompileStatusStore(appContext),
            version = selfBuild.versionName,
            debuggable = selfBuild.debuggable,
            loadSelfOptimized = {
                try {
                    prefs.selfCompile.first()?.let {
                        it.versionCode == selfBuild.versionCode && it.lastUpdateTime == selfBuild.lastUpdateTime
                    } ?: false
                } catch (_: Exception) {
                    false
                }
            },
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + kotlinx.coroutines.CoroutineExceptionHandler { _, _ -> }),
        )
    }

    /**
     * Launch-timing sequences. Created on first use; its retry also runs from
     * app start, the maintenance loop, and helper-up. Its own compiles
     * (restore path) report to [compileStatus]; timed/measure recompiles
     * report through the Compile screen's [doCompile][io.github.devjardins.sidespeed.ui.compile.CompileViewModel].
     */
    val launchRunner: LaunchRunner by lazy {
        LaunchRunner(
            commands = toolsCommands,
            store = toolsStore,
            prefs = prefs,
            compile = compileRunner,
            scope = appScope,
            awaitEngineIdle = { pkg -> maintenance.awaitNotRunning(pkg) },
            awaitSelfIdle = { selfCompiler.awaitIdle() },
            launchActivityOf = installedApps::launchActivity,
            versionOf = installedApps::versionOf,
            keepAlive = { MaintenanceService.start(appContext) },
            status = compileStatus,
            helperUp = { helper.ping() != null },
            labelOf = { pkg -> runCatching { installedApps.find(pkg)?.label }.getOrNull() },
        )
    }

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
            strings = stringLookup(appContext),
            measuring = { launchRunner.measuring.value },
            onHelperUp = { appScope.launch { launchRunner.restorePending() } },
            status = compileStatus,
        )
    }

    /** SideSpeed's own install identity; shared by [selfCompiler] and the Apps header pill. */
    val selfBuild: SelfBuild by lazy {
        val info = if (Build.VERSION.SDK_INT >= 33) {
            appContext.packageManager.getPackageInfo(
                appContext.packageName,
                PackageManager.PackageInfoFlags.of(0),
            )
        } else {
            @Suppress("DEPRECATION")
            appContext.packageManager.getPackageInfo(appContext.packageName, 0)
        }
        SelfBuild(
            versionCode = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else {
                @Suppress("DEPRECATION")
                info.versionCode.toLong()
            },
            lastUpdateTime = info.lastUpdateTime,
            debuggable = (info.applicationInfo?.flags ?: 0) and ApplicationInfo.FLAG_DEBUGGABLE != 0,
            versionName = info.versionName ?: "",
        )
    }

    /**
     * Compiles SideSpeed itself to speed once per install/update; call only while in the background.
     * Stays pending while a launch sequence is busy ([launchRunner] measuring non-null) and runs on
     * the next normal leave after it ends.
     */
    val selfCompiler: SelfCompiler by lazy {
        SelfCompiler(
            prefs = prefs,
            runCompile = { name, mode -> compileRunner.run(name, mode) },
            helperUp = { helper.ping() != null },
            ownPackage = appContext.packageName,
            versionCode = selfBuild.versionCode,
            lastUpdateTime = selfBuild.lastUpdateTime,
            debuggable = selfBuild.debuggable,
            status = compileStatus,
            measuring = { launchRunner.measuring.value },
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
