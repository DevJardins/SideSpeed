package io.github.devjardins.sidespeed

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import io.github.devjardins.sidespeed.apps.AppEntry
import io.github.devjardins.sidespeed.compile.CompileMode
import androidx.lifecycle.lifecycleScope
import io.github.devjardins.sidespeed.l10n.AppLanguage
import io.github.devjardins.sidespeed.maintenance.MaintenanceService
import io.github.devjardins.sidespeed.maintenance.isServiceNeeded
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import io.github.devjardins.sidespeed.status.TvAddress
import io.github.devjardins.sidespeed.tools.Animations
import io.github.devjardins.sidespeed.tools.UpdateCheck
import io.github.devjardins.sidespeed.tools.githubReleaseFetch
import io.github.devjardins.sidespeed.updates.pickVariant
import io.github.devjardins.sidespeed.updates.updateFor
import io.github.devjardins.sidespeed.update.installedSignerSha256
import io.github.devjardins.sidespeed.ui.about.AboutScreen
import io.github.devjardins.sidespeed.ui.about.AboutViewModel
import io.github.devjardins.sidespeed.ui.about.LanguageScreen
import io.github.devjardins.sidespeed.l10n.ShippedLanguages
import io.github.devjardins.sidespeed.l10n.languageOptions
import io.github.devjardins.sidespeed.l10n.nativeLanguageName
import io.github.devjardins.sidespeed.ui.apps.AppsScreen
import io.github.devjardins.sidespeed.ui.apps.AppsViewModel
import io.github.devjardins.sidespeed.ui.apps.AutoOptimizeScreen
import io.github.devjardins.sidespeed.ui.apps.UpdateBannerSlot
import io.github.devjardins.sidespeed.ui.home.HomeScreen
import io.github.devjardins.sidespeed.ui.tools.AnimationsCard
import io.github.devjardins.sidespeed.ui.tools.HealthCardSlot
import io.github.devjardins.sidespeed.BuildConfig
import io.github.devjardins.sidespeed.ui.tools.ExtrasCard
import io.github.devjardins.sidespeed.ui.tools.ExtrasScreen
import io.github.devjardins.sidespeed.ui.tools.HomeCard
import io.github.devjardins.sidespeed.ui.tools.SpaceCard
import io.github.devjardins.sidespeed.ui.tools.ToolsPlaceholder
import io.github.devjardins.sidespeed.ui.tools.ToolsScreen
import io.github.devjardins.sidespeed.ui.compile.CompileScreen
import io.github.devjardins.sidespeed.ui.compile.CompileViewModel
import io.github.devjardins.sidespeed.updates.ApkVariant
import io.github.devjardins.sidespeed.updates.AppUpdateUiState
import io.github.devjardins.sidespeed.ui.setup.SetupScreen
import io.github.devjardins.sidespeed.ui.setup.SetupViewModel
import io.github.devjardins.sidespeed.ui.setup.StrategyReadier
import io.github.devjardins.sidespeed.ui.text.UiText
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

    private val aboutVm: AboutViewModel by lazy {
        val c = (application as SideSpeedApp).container
        val versionName = runCatching {
            val info = if (android.os.Build.VERSION.SDK_INT >= 33) {
                packageManager.getPackageInfo(
                    packageName,
                    android.content.pm.PackageManager.PackageInfoFlags.of(0),
                )
            } else {
                @Suppress("DEPRECATION") packageManager.getPackageInfo(packageName, 0)
            }
            info.versionName
        }.getOrNull() ?: "0.0.0"
        ViewModelProvider(this, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                AboutViewModel(
                    UpdateCheck(
                        githubReleaseFetch(),
                        c.toolsStore,
                        System::currentTimeMillis,
                        versionName,
                    ),
                    startUpdate = { tag -> c.selfUpdater.start(tag) },
                    // Same debug hook as the Apps banner: debuggable builds act as if 9.9.9 shipped.
                    debugNewerTag = if (c.selfBuild.debuggable) "9.9.9" else null,
                ) as T
        })[AboutViewModel::class.java]
    }

    private val compileVm: CompileViewModel by lazy {
        val c = (application as SideSpeedApp).container
        ViewModelProvider(this, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                CompileViewModel(
                    c.compileRunner, c.statusChecker, c.prefs,
                    store = c.maintenanceStore,
                    launches = c.launchRunner,
                    status = c.compileStatus, helperUp = { c.helper.ping() != null },
                ) as T
        })[CompileViewModel::class.java]
    }

    override fun attachBaseContext(base: Context) = super.attachBaseContext(AppLanguage.wrap(base))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Self-compile only while SideSpeed is in the background: compiling the foreground app may kill it.
        // During a launch sequence (Measure / timed compile) it stays pending and runs on the next leave.
        lifecycle.addObserver(LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                lifecycleScope.launch {
                    runCatching { (application as SideSpeedApp).container.selfCompiler.maybeCompile() }
                }
            }
        })
        lifecycleScope.launch {
            val c = (application as SideSpeedApp).container
            if (isServiceNeeded(c.maintenanceStore, c.toolsStore)) MaintenanceService.start(applicationContext)
            // Retry a launch restore left pending by an interrupted measurement (no-op when none).
            runCatching { c.launchRunner.restorePending() }
        }
        setContent {
            val icons = (application as SideSpeedApp).container.appIcons
            SideSpeedTheme {
                // Not saveable: after process death HelperClient has no verified port, so always re-check
                // through Setup (a fast ping when the helper is alive).
                var ready by remember { mutableStateOf(false) }
                var selectedPkg by rememberSaveable { mutableStateOf<String?>(null) }
                var showTools by rememberSaveable { mutableStateOf(false) }
                var showAbout by rememberSaveable { mutableStateOf(false) }
                var showLanguage by rememberSaveable { mutableStateOf(false) }
                // Set when the Language screen closes, so About focuses its Language row on return.
                var focusLanguageRow by rememberSaveable { mutableStateOf(false) }
                val languageRowFocus = remember { FocusRequester() }
                var showMyApps by rememberSaveable { mutableStateOf(false) }
                // Last Home tile opened: Home leaves composition on these screens,
                // so this survives and decides which tile takes focus on return.
                var lastHomeTile by rememberSaveable {
                    mutableStateOf(io.github.devjardins.sidespeed.ui.home.HomeTile.MY_APPS.name)
                }
                var appsFilterName by rememberSaveable {
                    mutableStateOf(io.github.devjardins.sidespeed.ui.apps.AppsFilter.ALL.name)
                }
                // Last app opened from My Apps: survives the Compile screen (where
                // My Apps leaves composition) so Back can scroll to it and refocus it.
                var lastAppsPkg by rememberSaveable { mutableStateOf<String?>(null) }
                var showAutoOptimize by rememberSaveable { mutableStateOf(false) }
                var showExtras by rememberSaveable { mutableStateOf(false) }
                val forcedExtrasListId = remember {
                    if (BuildConfig.DEBUG) intent.getStringExtra("extras_list_id") else null
                }
                // Shown in the update banner and compared against the latest GitHub tag.
                val currentVersion = remember {
                    runCatching {
                        val info = if (android.os.Build.VERSION.SDK_INT >= 33) {
                            packageManager.getPackageInfo(
                                packageName,
                                android.content.pm.PackageManager.PackageInfoFlags.of(0),
                            )
                        } else {
                            @Suppress("DEPRECATION") packageManager.getPackageInfo(packageName, 0)
                        }
                        info.versionName
                    }.getOrNull() ?: "0.0.0"
                }
                var selectedMode by rememberSaveable { mutableStateOf(CompileMode.SPEED) }
                var selected by remember { mutableStateOf<AppEntry?>(null) }
                val app = selected ?: selectedPkg?.let { p -> remember(p) { appsVm.findApp(p) } }
                // The header's address line follows the network: recomputed off the main thread on
                // every resume and whenever the default network's link properties change or drop.
                var tvAddress by remember { mutableStateOf<UiText?>(null) }
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
                    !ready -> SetupScreen(setupVm, onReady = {
                        ready = true
                        // The helper is up now: retry a pending launch restore.
                        lifecycleScope.launch {
                            runCatching { (application as SideSpeedApp).container.launchRunner.restorePending() }
                        }
                    })
                    showTools && showExtras && app == null -> {
                        val c = (application as SideSpeedApp).container
                        ExtrasScreen(
                            extras = c.extras(forcedExtrasListId),
                            index = c.extrasIndex(),
                            homeSwitcher = c.homeSwitcher,
                            onBack = { showExtras = false },
                        )
                    }
                    showTools && app == null -> ToolsScreen(
                        // Worker A owns space; worker C owns health + animations.
                        health = {
                            HealthCardSlot(
                                (application as SideSpeedApp).container.deviceInfo,
                                { (application as SideSpeedApp).container.helper.ping() != null },
                            )
                        },
                        space = {
                            val c = (application as SideSpeedApp).container
                            SpaceCard(
                                store = c.toolsStore,
                                deviceInfo = c.deviceInfo,
                                cleanup = c.cleanup,
                                onThresholdChanged = {
                                    lifecycleScope.launch {
                                        if (isServiceNeeded(c.maintenanceStore, c.toolsStore)) MaintenanceService.start(applicationContext)
                                    }
                                },
                            )
                        },
                        animations = {
                            val tools = (application as SideSpeedApp).container
                            AnimationsCard(
                                Animations(tools.toolsCommands, tools.toolsStore, isFireOs = tools.isFireOs),
                                tools.toolsStore,
                            )
                        },
                        extras = {
                            ExtrasCard(onOpen = { showExtras = true })
                        },
                        home = {
                            val c = (application as SideSpeedApp).container
                            HomeCard(c.homeSwitcher, icons)
                        },
                        onBack = { showTools = false },
                    )
                    showLanguage && app == null -> {
                        // Debug builds list the pseudolocales too; release builds never do.
                        val shipped = remember {
                            if (BuildConfig.DEBUG) ShippedLanguages.TAGS + listOf("en-XA", "ar-XB")
                            else ShippedLanguages.TAGS
                        }
                        val chosen = remember { AppLanguage.chosen(this@MainActivity) }
                        val deviceLabel = stringResource(R.string.about_language_same_as_device)
                        val options = remember(shipped, chosen) {
                            languageOptions(shipped, chosen, ::nativeLanguageName)
                        }
                        LanguageScreen(
                            deviceLabel = deviceLabel,
                            options = options,
                            onChoose = { tag ->
                                // Close the list before choosing: the activity may recreate,
                                // and the list must not reopen after it.
                                showLanguage = false
                                focusLanguageRow = true
                                AppLanguage.choose(this@MainActivity, tag)
                            },
                            onBack = {
                                showLanguage = false
                                focusLanguageRow = true
                            },
                        )
                    }
                    showAbout && app == null -> AboutScreen(
                        versionName = currentVersion,
                        viewModel = aboutVm,
                        selfUpdater = (application as SideSpeedApp).container.selfUpdater,
                        busy = (application as SideSpeedApp).container.updateBusy,
                        onBack = { showAbout = false },
                        onOpenLanguage = { showLanguage = true },
                        languageRowFocus = languageRowFocus,
                        focusLanguageRow = focusLanguageRow,
                    )
                    showAutoOptimize && app == null -> AutoOptimizeScreen(
                        mode = (application as SideSpeedApp).container.maintenanceStore.mode,
                        onMode = { chosen ->
                            lifecycleScope.launch {
                                val c = (application as SideSpeedApp).container
                                c.maintenanceStore.setMode(chosen)
                                if (isServiceNeeded(c.maintenanceStore, c.toolsStore)) MaintenanceService.start(applicationContext)
                            }
                        },
                        history = (application as SideSpeedApp).container.maintenanceStore.history,
                        onBack = { showAutoOptimize = false },
                    )
                    showMyApps && app == null -> AppsScreen(
                        appsVm,
                        lastCompilesFlow = (application as SideSpeedApp).container.prefs.lastCompiles(),
                        icon = icons,
                        maintenance = (application as SideSpeedApp).container.maintenance.state,
                        autoOptimizeMode = (application as SideSpeedApp).container.maintenanceStore.mode,
                        maintained = (application as SideSpeedApp).container.maintenanceStore.maintained,
                        appUpdates = (application as SideSpeedApp).container.appUpdateCatalog,
                        onToggleKeep = { pkg, on ->
                            lifecycleScope.launch {
                                val c = (application as SideSpeedApp).container
                                c.maintenanceStore.setMaintained(pkg, on)
                                if (isServiceNeeded(c.maintenanceStore, c.toolsStore)) MaintenanceService.start(applicationContext)
                            }
                        },
                        onSelect = { a, m ->
                            selected = a
                            selectedPkg = a.packageName
                            lastAppsPkg = a.packageName
                            selectedMode = m
                        },
                        // Leaving My Apps forgets the opened row, so the next visit starts on the filter.
                        onBack = { showMyApps = false; lastAppsPkg = null },
                        filterName = appsFilterName,
                        onFilterChange = { appsFilterName = it },
                        returnToPackage = lastAppsPkg,
                    )
                    app == null -> {
                        val c = (application as SideSpeedApp).container
                        HomeScreen(
                            appsVm,
                            maintenance = c.maintenance.state,
                            autoOptimizeMode = c.maintenanceStore.mode,
                            appUpdates = c.appUpdateCatalog,
                            onVisible = {
                                lifecycleScope.launch(Dispatchers.IO) {
                                    runCatching { c.appUpdateCheck.checkIfDue() }
                                }
                            },
                        onOpenMyApps = {
                            lastHomeTile = io.github.devjardins.sidespeed.ui.home.HomeTile.MY_APPS.name
                            showMyApps = true
                        },
                        onOpenAutoOptimize = {
                            lastHomeTile = io.github.devjardins.sidespeed.ui.home.HomeTile.AUTO_OPTIMIZE.name
                            showAutoOptimize = true
                        },
                        onOpenTools = {
                            lastHomeTile = io.github.devjardins.sidespeed.ui.home.HomeTile.TOOLS.name
                            showTools = true
                        },
                        onOpenAbout = {
                            lastHomeTile = io.github.devjardins.sidespeed.ui.home.HomeTile.ABOUT.name
                            showAbout = true
                            focusLanguageRow = false
                        },
                        banner = {
                            val container = (application as SideSpeedApp).container
                            UpdateBannerSlot(
                                remember(currentVersion) {
                                    UpdateCheck(
                                        githubReleaseFetch(),
                                        container.toolsStore,
                                        System::currentTimeMillis,
                                        currentVersion,
                                    )
                                },
                                container.toolsStore,
                                currentVersion,
                                container.selfUpdater,
                                container.updateBusy,
                            )
                        },
                        tvAddress = tvAddress,
                        selfCompile = (application as SideSpeedApp).container.prefs.selfCompile,
                        selfVersionCode = (application as SideSpeedApp).container.selfBuild.versionCode,
                        selfLastUpdateTime = (application as SideSpeedApp).container.selfBuild.lastUpdateTime,
                        selfDebuggable = (application as SideSpeedApp).container.selfBuild.debuggable,
                        versionName = currentVersion,
                        focusedTile = io.github.devjardins.sidespeed.ui.home.homeTileFromSaved(lastHomeTile),
                        )
                    }
                    else -> {
                        val running = compileVm.state.collectAsState().value.running
                        // Blocked while a launch sequence is busy: leaving would hide its
                        // progress and invite a second sequence (the runner restores regardless).
                        val launchBusy = compileVm.launch.collectAsState().value.busy != null
                        val back = { if (!running && !launchBusy) { selected = null; selectedPkg = null } }
                        BackHandler { back() }
                        val container = (application as SideSpeedApp).container
                        val catalog by container.appUpdateCatalog.collectAsState(initial = emptyMap())
                        val available = updateFor(app, app.versionName, catalog[app.packageName])
                        // Reopening the same app keeps its live update: reset only when a
                        // different package's update is idle or finished, never while it runs.
                        // Leaving the screen (Back) cancels nothing and is never blocked.
                        LaunchedEffect(app.packageName) {
                            val updater = container.appUpdater
                            val current = updater.pkg.value
                            if (current != null && current != app.packageName) {
                                when (updater.state.value) {
                                    is AppUpdateUiState.Downloading,
                                    is AppUpdateUiState.Checking,
                                    is AppUpdateUiState.Installing,
                                        -> Unit // Another app is updating: leave it running.
                                    else -> updater.reset()
                                }
                            }
                        }
                        // Signer reads go through PackageManager: off the main thread.
                        val pickedVariant by produceState<ApkVariant?>(
                            null, available?.apk, app.packageName to app.versionCode,
                        ) {
                            value = withContext(Dispatchers.IO) {
                                val variants = available?.apk.orEmpty()
                                if (variants.isEmpty()) {
                                    null
                                } else {
                                    pickVariant(
                                        variants,
                                        android.os.Build.SUPPORTED_ABIS.toList(),
                                        android.os.Build.VERSION.SDK_INT,
                                        app.versionCode,
                                        installedSignerSha256(packageManager, app.packageName),
                                    )
                                }
                            }
                        }
                        val appUpdateState by container.appUpdater.state.collectAsState()
                        val updatePkg by container.appUpdater.pkg.collectAsState()
                        CompileScreen(
                            compileVm, app, selectedMode,
                            icon = icons,
                            onModeChange = { selectedMode = it },
                            onBack = back,
                            onKeepOptimized = { MaintenanceService.start(applicationContext) },
                            onCheckHelper = { selected = null; selectedPkg = null; setupVm.checkAgain(); ready = false },
                            availableUpdate = available,
                            pickedVariant = pickedVariant,
                            appUpdateState = appUpdateState,
                            updatePkg = updatePkg,
                            onUpdateNow = {
                                pickedVariant?.let { variant ->
                                    available?.let { update ->
                                        container.appUpdater.start(app.packageName, variant, update.versionName)
                                    }
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}
