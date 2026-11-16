package io.github.devjardins.sidespeed.companion.desktop

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.res.loadImageBitmap
import androidx.compose.ui.res.useResource
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Notification
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberTrayState
import androidx.compose.ui.window.rememberWindowState
import io.github.devjardins.sidespeed.companion.discovery.Discovery
import io.github.devjardins.sidespeed.companion.discovery.SocketAdbProbe
import io.github.devjardins.sidespeed.companion.discovery.SweepSource
import io.github.devjardins.sidespeed.companion.net.TrustMatcher
import io.github.devjardins.sidespeed.companion.store.CompanionSettings
import io.github.devjardins.sidespeed.companion.store.JsonFileCompanionStore
import io.github.devjardins.sidespeed.companion.tv.DesktopDirs
import io.github.devjardins.sidespeed.companion.tv.HelperKeeper
import io.github.devjardins.sidespeed.companion.tv.KadbTvConnector
import io.github.devjardins.sidespeed.companion.upkeep.TcpScanner
import io.github.devjardins.sidespeed.companion.upkeep.Trigger
import io.github.devjardins.sidespeed.companion.upkeep.Upkeep
import io.github.devjardins.sidespeed.companion.ui.CompanionApp
import io.github.devjardins.sidespeed.companion.ui.CompanionViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import kotlin.system.exitProcess

/** Holds a lock file for the life of the process so two copies never share the store. Null if already running. */
private const val SHOW_REQUEST = "show.request"

private fun singleInstanceLock(dir: File): RandomAccessFile? {
    dir.mkdirs()
    val raf = RandomAccessFile(File(dir, "instance.lock"), "rw")
    val lock = try { raf.channel.tryLock() } catch (e: Exception) { null }
    return if (lock == null) { raf.close(); null } else raf
}

fun main() {
    val appData = DesktopDirs.appData()
    val lock = singleInstanceLock(appData)
    if (lock == null) {
        // Already running: ask that copy to show its window, then leave.
        File(appData, SHOW_REQUEST).writeText("show")
        exitProcess(0)
    }

    // The store keeps its own subdirectory; the app-data folder itself is never re-permissioned.
    val store = JsonFileCompanionStore(File(appData, "store"))
    val fingerprints = DesktopFingerprintSource()
    val connector = KadbTvConnector(DesktopDirs.keyDir())
    val scanner = TcpScanner()
    val upkeep = Upkeep(store, fingerprints, connector, HelperKeeper(), scanner) { System.currentTimeMillis() }
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @OptIn(ExperimentalCoroutinesApi::class)
    val discovery = Discovery(listOf(SweepSource), SocketAdbProbe(), Dispatchers.IO.limitedParallelism(Discovery.PARALLELISM))
    val vm = CompanionViewModel(store, fingerprints, upkeep, connector, discovery, scope)

    application {
        val tray = rememberTrayState()
        val icon = remember { BitmapPainter(useResource("tray.png", ::loadImageBitmap)) }
        val windowIcon = remember { BitmapPainter(useResource("icons/app.png", ::loadImageBitmap)) }
        var windowVisible by remember { mutableStateOf(true) }
        var paused by remember { mutableStateOf(false) }
        val state by vm.state.collectAsState()

        suspend fun runAndNotify(trigger: Trigger) {
            val report = upkeep.runOnce(trigger)
            if (store.settings().notifications) {
                report.restartedNames.forEach { tray.sendNotification(Notification("SideSpeed Companion", "SideSpeed restarted on $it")) }
            }
            vm.refreshNow()
        }

        LaunchedEffect(Unit) { vm.onOpen() }
        // Check every intervalMinutes, unless paused. Keyed on the interval, so a change restarts the wait.
        LaunchedEffect(state.settings.intervalMinutes) {
            while (true) {
                delay(state.settings.intervalMinutes * 60_000L)
                if (!paused) runAndNotify(Trigger.BACKGROUND)
            }
        }
        // Every 30 s: joining a Trusted Network triggers a check. Nothing is read while paused or before a
        // Trusted Network exists, so the poll spawns no processes then.
        LaunchedEffect(Unit) {
            var last = fingerprints.current()
            while (true) {
                delay(30_000)
                if (paused || state.firstRun) continue
                val now = fingerprints.current()
                if (now != last) {
                    if (TrustMatcher.currentTrusted(now, store.trustedNetworks()) != null) runAndNotify(Trigger.NETWORK_JOINED)
                    last = now
                }
            }
        }
        // A second launch leaves a request file; show the window for it.
        LaunchedEffect(Unit) {
            val request = File(appData, SHOW_REQUEST)
            while (true) {
                delay(1_000)
                if (request.exists()) { request.delete(); windowVisible = true }
            }
        }
        // "Start when I log in" follows the saved setting, applied off the UI thread. A loaded "on" is
        // re-applied (it heals a moved app); a loaded "off" needs nothing. If enabling fails the setting goes back off.
        var autostartSeen by remember { mutableStateOf<Boolean?>(null) }
        LaunchedEffect(state.settings.startAtLogin, state.loaded) {
            if (!state.loaded) return@LaunchedEffect
            val want = state.settings.startAtLogin
            val first = autostartSeen == null
            autostartSeen = want
            if (first && !want) return@LaunchedEffect
            val ok = withContext(Dispatchers.IO) { Autostart.set(want) }
            if (!ok && want) vm.updateSettings(state.settings.copy(startAtLogin = false))
        }

        Tray(
            icon = icon,
            tooltip = "SideSpeed Companion",
            state = tray,
            onAction = { windowVisible = true },
            menu = {
                Item("Restart now", onClick = { vm.restartNow() })
                Item("Open SideSpeed Companion", onClick = { windowVisible = true })
                Item(if (paused) "Resume checks" else "Pause checks", onClick = { paused = !paused })
                Separator()
                Item("Quit", onClick = { lock.close(); exitApplication() })
            },
        )
        // Closing the window keeps the app in the tray.
        Window(
            onCloseRequest = { windowVisible = false },
            visible = windowVisible,
            title = "SideSpeed Companion",
            icon = windowIcon,
            state = rememberWindowState(size = DpSize(420.dp, 640.dp)),
            resizable = true,
        ) {
            CompanionApp(vm, desktop = true)
        }
    }
}
