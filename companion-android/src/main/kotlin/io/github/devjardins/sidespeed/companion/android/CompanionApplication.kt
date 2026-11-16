package io.github.devjardins.sidespeed.companion.android

import android.app.Application
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import io.github.devjardins.sidespeed.companion.net.TrustMatcher
import io.github.devjardins.sidespeed.companion.store.JsonFileCompanionStore
import io.github.devjardins.sidespeed.companion.tv.AndroidDirs
import io.github.devjardins.sidespeed.companion.tv.HelperKeeper
import io.github.devjardins.sidespeed.companion.tv.KadbTvConnector
import io.github.devjardins.sidespeed.companion.upkeep.Scanner
import io.github.devjardins.sidespeed.companion.upkeep.TcpScanner
import io.github.devjardins.sidespeed.companion.upkeep.Trigger
import io.github.devjardins.sidespeed.companion.upkeep.Upkeep
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class Graph(app: Application) {
    // The store keeps its own subdirectory; nothing outside it is touched.
    val store = JsonFileCompanionStore(java.io.File(app.filesDir, "companion"))
    val fingerprints = AndroidFingerprintSource(app)
    val connector = KadbTvConnector(AndroidDirs.keyDir(app))
    val scanner: Scanner = TcpScanner()
    val upkeep = Upkeep(store, fingerprints, connector, HelperKeeper(), scanner) { System.currentTimeMillis() }
    val notifier = Notifier(app)
}

class CompanionApplication : Application() {
    lateinit var graph: Graph
        private set
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        graph = Graph(this)
        appScope.launch { UpkeepWorker.schedule(this@CompanionApplication, graph.store.settings().intervalMinutes, replace = false) }
        watchNetwork()
    }

    /** Runs a NETWORK_JOINED check when the network's fingerprint changes to a Trusted Network. */
    private fun watchNetwork() {
        val cm = getSystemService(ConnectivityManager::class.java)
        var last: io.github.devjardins.sidespeed.companion.net.NetworkFingerprint? = null
        var pending: Job? = null
        val onChange = {
            pending?.cancel()
            pending = appScope.launch {
                // DNS can be briefly empty right after joining: let it settle before reading the fingerprint.
                delay(3_000)
                val now = graph.fingerprints.current()
                if (now != last) {
                    last = now
                    if (TrustMatcher.currentTrusted(now, graph.store.trustedNetworks()) != null) {
                        val report = graph.upkeep.runOnce(Trigger.NETWORK_JOINED)
                        if (graph.store.settings().notifications) report.restartedNames.forEach { graph.notifier.restarted(it) }
                    }
                }
            }
            Unit
        }
        cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { onChange() }
            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) { onChange() }
        })
    }
}
