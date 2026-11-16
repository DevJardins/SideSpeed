package io.github.devjardins.sidespeed

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import io.github.devjardins.sidespeed.apps.AppEntry
import io.github.devjardins.sidespeed.compile.CompileMode
import androidx.lifecycle.lifecycleScope
import io.github.devjardins.sidespeed.maintenance.MaintenanceService
import io.github.devjardins.sidespeed.maintenance.isArmed
import kotlinx.coroutines.launch
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
                CompileViewModel(c.compileRunner, c.statusChecker, c.prefs) as T
        })[CompileViewModel::class.java]
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
                when {
                    !ready -> SetupScreen(setupVm, onReady = { ready = true })
                    app == null -> AppsScreen(
                        appsVm,
                        lastCompile = (application as SideSpeedApp).container.prefs::lastCompile,
                        icon = icons,
                        maintenance = (application as SideSpeedApp).container.maintenance.state,
                        history = (application as SideSpeedApp).container.maintenanceStore.history,
                        onSelect = { a, m -> selected = a; selectedPkg = a.packageName; selectedMode = m },
                    )
                    else -> {
                        val running = compileVm.state.collectAsState().value.running
                        val back = { if (!running) { selected = null; selectedPkg = null } }
                        BackHandler { back() }
                        CompileScreen(
                            compileVm, app, selectedMode,
                            icon = icons,
                            onBack = back,
                            onCheckHelper = { selected = null; selectedPkg = null; setupVm.checkAgain(); ready = false },
                        )
                    }
                }
            }
        }
    }
}
