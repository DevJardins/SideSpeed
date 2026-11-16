package io.github.devjardins.sidespeed.companion.android

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.lifecycleScope
import io.github.devjardins.sidespeed.companion.ui.CompanionApp
import io.github.devjardins.sidespeed.companion.ui.CompanionViewModel
import kotlinx.coroutines.Dispatchers

class MainActivity : ComponentActivity() {
    private lateinit var vm: CompanionViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val g = (application as CompanionApplication).graph
        vm = CompanionViewModel(g.store, g.fingerprints, g.upkeep, g.connector, g.scanner, lifecycleScope)
        setContent {
            val state by vm.state.collectAsState()
            val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
            // The notification permission is asked only when the user turns notifications on (Android 13+).
            LaunchedEffect(state.settings.notifications) {
                if (state.settings.notifications && Build.VERSION.SDK_INT >= 33) {
                    askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
            val interval = state.settings.intervalMinutes
            val first = remember { booleanArrayOf(true) }
            LaunchedEffect(interval, state.loaded) {
                if (!state.loaded) return@LaunchedEffect
                // The first value is what the app already scheduled; only later changes replace the schedule.
                if (first[0]) first[0] = false else UpkeepWorker.schedule(applicationContext, interval, replace = true)
            }
            CompanionApp(vm, desktop = false)
        }
    }

    override fun onStart() {
        super.onStart()
        vm.onOpen()
    }
}
