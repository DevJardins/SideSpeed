package io.github.devjardins.sidespeed.companion.android

import android.app.Application
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import io.github.devjardins.sidespeed.companion.discovery.Discovery
import io.github.devjardins.sidespeed.companion.discovery.MdnsSource
import io.github.devjardins.sidespeed.companion.discovery.SocketAdbProbe
import io.github.devjardins.sidespeed.companion.discovery.SocketDatagramQuery
import io.github.devjardins.sidespeed.companion.discovery.SsdpSource
import io.github.devjardins.sidespeed.companion.discovery.SweepSource
import io.github.devjardins.sidespeed.companion.net.TrustMatcher
import io.github.devjardins.sidespeed.companion.store.JsonFileCompanionStore
import io.github.devjardins.sidespeed.companion.android.update.CompanionDownload
import io.github.devjardins.sidespeed.companion.android.update.CompanionUpdater
import io.github.devjardins.sidespeed.companion.android.update.clearStaleUpdateFiles
import io.github.devjardins.sidespeed.companion.android.update.installedSignerSha256
import io.github.devjardins.sidespeed.companion.android.update.readApkFacts
import io.github.devjardins.sidespeed.companion.tv.AndroidDirs
import io.github.devjardins.sidespeed.companion.tv.HelperKeeper
import io.github.devjardins.sidespeed.companion.tv.KadbTvConnector
import io.github.devjardins.sidespeed.companion.upkeep.Scanner
import io.github.devjardins.sidespeed.companion.upkeep.ProbeScanner
import io.github.devjardins.sidespeed.companion.upkeep.Trigger
import io.github.devjardins.sidespeed.companion.upkeep.Upkeep
import io.github.devjardins.sidespeed.companion.ui.CompanionViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch

class Graph(app: Application, scope: CoroutineScope) {
    // The store keeps its own subdirectory; nothing outside it is touched.
    val store = JsonFileCompanionStore(java.io.File(app.filesDir, "companion"))
    val fingerprints = AndroidFingerprintSource(app)
    val connector = KadbTvConnector(AndroidDirs.keyDir(app))
    @OptIn(ExperimentalCoroutinesApi::class)
    val scanner: Scanner = ProbeScanner(SocketAdbProbe(), Dispatchers.IO.limitedParallelism(Discovery.PARALLELISM))
    val upkeep = Upkeep(store, fingerprints, connector, HelperKeeper(), scanner) { System.currentTimeMillis() }
    val notifier = Notifier(app)

    @OptIn(ExperimentalCoroutinesApi::class)
    val discovery = Discovery(
        listOf(MdnsSource(SocketDatagramQuery()), SsdpSource(SocketDatagramQuery()), SweepSource),
        SocketAdbProbe(),
        Dispatchers.IO.limitedParallelism(Discovery.PARALLELISM),
    )

    /** One view model for the whole process: rotating or recreating the Activity doesn't restart it. */
    val viewModel = CompanionViewModel(store, fingerprints, upkeep, connector, discovery, scope)

    /** Self-update: check state and installer for the About screen. */
    val updater = CompanionUpdater(
        app,
        BuildConfig.VERSION_NAME,
        CompanionDownload(CompanionDownload::httpOpen),
        { file -> readApkFacts(app.packageManager, file) },
        { installedSignerSha256(app.packageManager, app.packageName) },
        scope,
    )
}

class CompanionApplication : Application() {
    lateinit var graph: Graph
        private set
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        clearStaleUpdateFiles(this)
        graph = Graph(this, appScope)
        appScope.launch { UpkeepWorker.schedule(this@CompanionApplication, graph.store.settings().intervalMinutes, replace = false) }
        watchNetwork()
    }

    /**
     * Runs a NETWORK_JOINED check when the network's fingerprint changes to a Trusted Network. Callbacks only
     * emit an event; one collector debounces them (cancelling just the wait, never a running check) and is the
     * only code touching `last`, so there is no shared mutable state. `last` starts as the fingerprint at launch,
     * so starting the process (for example for a periodic run) isn't a join.
     */
    @OptIn(FlowPreview::class)
    private fun watchNetwork() {
        val cm = getSystemService(ConnectivityManager::class.java)
        val events = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
        appScope.launch {
            var last = graph.fingerprints.current()
            fun callback() = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) { events.tryEmit(Unit) }
                override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) { events.tryEmit(Unit) }
                override fun onLost(network: Network) { events.tryEmit(Unit) }
            }
            cm.registerDefaultNetworkCallback(callback())
            // Under a VPN the default network stays the VPN when the Wi-Fi changes, so watch Wi-Fi/Ethernet too.
            val lan = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
                .build()
            cm.registerNetworkCallback(lan, callback())
            // DNS can be briefly empty right after joining: let it settle before reading the fingerprint.
            events.debounce(3_000).collect {
                val now = graph.fingerprints.current()
                if (now != last) {
                    if (TrustMatcher.currentTrusted(now, graph.store.trustedNetworks()) != null) {
                        val report = graph.upkeep.runOnce(Trigger.NETWORK_JOINED)
                        if (graph.store.settings().notifications) report.restartedNames.forEach { graph.notifier.restarted(it) }
                    }
                    // Only after the check has finished, so an interrupted one is retried by the next event.
                    last = now
                }
            }
        }
    }
}
