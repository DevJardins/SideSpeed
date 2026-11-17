package io.github.devjardins.sidespeed

import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import io.github.devjardins.sidespeed.apps.AppEntry
import io.github.devjardins.sidespeed.compile.CompileMode
import androidx.lifecycle.lifecycleScope
import io.github.devjardins.sidespeed.maintenance.MaintenanceService
import io.github.devjardins.sidespeed.maintenance.isArmed
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import io.github.devjardins.sidespeed.status.TvAddress
import io.github.devjardins.sidespeed.ui.apps.AppsScreen
import io.github.devjardins.sidespeed.ui.apps.AppsViewModel
import io.github.devjardins.sidespeed.ui.compile.CompileScreen
import io.github.devjardins.sidespeed.ui.compile.CompileViewModel
import io.github.devjardins.sidespeed.ui.setup.SetupScreen
import io.github.devjardins.sidespeed.ui.setup.SetupViewModel
import io.github.devjardins.sidespeed.ui.setup.StrategyReadier
import io.github.devjardins.sidespeed.ui.theme.SideSpeedTheme

class MainActivity : ComponentActivity() {
    private val setupVm: SetupViewModel by lazy {
        val c = (application as SideSpeedApp).container
        ViewModelProvider(this, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                SetupViewModel(StrategyReadier(c.strategy), c.adb, c.prefs, c.isFireOs, c::wifiIp, c.settings) as T
        })[SetupViewModel::class.java]
    }

    private val appsVm: AppsViewModel by lazy {
        val c = (application as SideSpeedApp).container
        ViewModelProvider(this, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = AppsViewModel(c.installedApps, c.statusChecker) as T
        })[AppsViewModel::class.java]
    }

    private val compileVm: CompileViewModel by lazy {
        val c = (application as SideSpeedApp).container
        ViewModelProvider(this, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                CompileViewModel(c.compileRunner, c.statusChecker, c.prefs, store = c.maintenanceStore) as T
        })[CompileViewModel::class.java]
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Self-compile only while SideSpeed is in the background: compiling the foreground app may kill it.
        lifecycle.addObserver(LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                lifecycleScope.launch {
                    runCatching { (application as SideSpeedApp).container.selfCompiler.maybeCompile() }
                }
            }
        })
        lifecycleScope.launch {
            if (isArmed((application as SideSpeedApp).container.maintenanceStore)) MaintenanceService.start(applicationContext)
        }
        setContent {
            val icons = (application as SideSpeedApp).container.appIcons
            SideSpeedTheme {
                // Not saveable: after process death HelperClient has no verified port, so always re-check
                // through Setup (a fast ping when the helper is alive).
                var ready by remember { mutableStateOf(false) }
                var selectedPkg by rememberSaveable { mutableStateOf<String?>(null) }
                var selectedMode by rememberSaveable { mutableStateOf(CompileMode.SPEED) }
                var selected by remember { mutableStateOf<AppEntry?>(null) }
                val app = selected ?: selectedPkg?.let { p -> remember(p) { appsVm.manualEntryApp(p) } }
                // The header's address line follows the network: recomputed off the main thread on
                // every resume and whenever the default network's link properties change or drop.
                var tvAddress by remember { mutableStateOf<String?>(null) }
                val refreshScope = rememberCoroutineScope()
                fun refreshTvAddress() {
                    refreshScope.launch {
                        val label = withContext(Dispatchers.IO) {
                            TvAddress.label((application as SideSpeedApp).container.tvAddresses())
                        }
                        tvAddress = label
                    }
                }
                DisposableEffect(Unit) {
                    val connectivity = applicationContext.getSystemService(ConnectivityManager::class.java)
                    val callback = object : ConnectivityManager.NetworkCallback() {
                        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) =
                            refreshTvAddress()

                        override fun onLost(network: Network) = refreshTvAddress()
                    }
                    val observer = LifecycleEventObserver { _, event ->
                        if (event == Lifecycle.Event.ON_RESUME) refreshTvAddress()
                    }
                    lifecycle.addObserver(observer)
                    var registered = false
                    runCatching {
                        connectivity?.registerDefaultNetworkCallback(callback)
                        registered = true
                    }
                    refreshTvAddress()
                    onDispose {
                        lifecycle.removeObserver(observer)
                        if (registered) runCatching { connectivity?.unregisterNetworkCallback(callback) }
                    }
                }
                when {
                    !ready -> SetupScreen(setupVm, onReady = { ready = true })
                    app == null -> AppsScreen(
                        appsVm,
                        lastCompilesFlow = (application as SideSpeedApp).container.prefs.lastCompiles(),
                        icon = icons,
                        maintenance = (application as SideSpeedApp).container.maintenance.state,
                        history = (application as SideSpeedApp).container.maintenanceStore.history,
                        autoOptimizeMode = (application as SideSpeedApp).container.maintenanceStore.mode,
                        onAutoOptimizeMode = { chosen ->
                            lifecycleScope.launch {
                                val store = (application as SideSpeedApp).container.maintenanceStore
                                store.setMode(chosen)
                                if (isArmed(store)) MaintenanceService.start(applicationContext)
                            }
                        },
                        onSelect = { a, m -> selected = a; selectedPkg = a.packageName; selectedMode = m },
                        tvAddress = tvAddress,
                    )
                    else -> {
                        val running = compileVm.state.collectAsState().value.running
                        val back = { if (!running) { selected = null; selectedPkg = null } }
                        BackHandler { back() }
                        CompileScreen(
                            compileVm, app, selectedMode,
                            icon = icons,
                            onBack = back,
                            onKeepOptimized = { MaintenanceService.start(applicationContext) },
                            onCheckHelper = { selected = null; selectedPkg = null; setupVm.checkAgain(); ready = false },
                        )
                    }
                }
            }
        }
    }
}
