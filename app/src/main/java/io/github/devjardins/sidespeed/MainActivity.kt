package io.github.devjardins.sidespeed

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
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
                SetupViewModel(StrategyReadier(c.strategy), c.adb, c.prefs, c.isFireOs, c::wifiIp) as T
        })[SetupViewModel::class.java]
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SideSpeedTheme {
                var ready by rememberSaveable { mutableStateOf(false) }
                if (!ready) {
                    SetupScreen(setupVm, onReady = { ready = true })
                } else {
                    // Apps list arrives in a later task.
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("SideSpeed", style = MaterialTheme.typography.displayMedium)
                    }
                }
            }
        }
    }
}
