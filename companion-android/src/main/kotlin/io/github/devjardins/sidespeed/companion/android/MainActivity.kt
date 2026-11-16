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
import io.github.devjardins.sidespeed.companion.ui.CompanionApp
import io.github.devjardins.sidespeed.companion.ui.CompanionViewModel

class MainActivity : ComponentActivity() {
    private lateinit var vm: CompanionViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        vm = (application as CompanionApplication).graph.viewModel
        // A recreated Activity (rotation) isn't "opening the app": don't check every TV again.
        skipNextOpen = savedInstanceState != null
        setContent {
            val state by vm.state.collectAsState()
            val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
            // The notification permission is asked only when the user turns notifications on (Android 13+).
            val previous = remember { arrayOf<Boolean?>(null) }
            LaunchedEffect(state.settings.notifications, state.loaded) {
                if (!state.loaded) return@LaunchedEffect
                val was = previous[0]
                previous[0] = state.settings.notifications
                if (shouldAskForNotifications(was, state.settings.notifications) && Build.VERSION.SDK_INT >= 33) {
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
        if (skipNextOpen) skipNextOpen = false else vm.onOpen()
    }

    private var skipNextOpen = false

    companion object {
        /** Ask only when the user turns notifications on (off to on), never because they are already on. */
        fun shouldAskForNotifications(was: Boolean?, now: Boolean): Boolean = was == false && now
    }
}
