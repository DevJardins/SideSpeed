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
import com.flyfishxu.kadb.mdns.KadbMdnsJvm
import com.flyfishxu.kadb.mdns.MdnsConfig
import com.flyfishxu.kadb.mdns.MdnsServiceType
import io.github.devjardins.sidespeed.companion.discovery.Discovery
import io.github.devjardins.sidespeed.companion.discovery.KadbMdnsConnectPorts
import io.github.devjardins.sidespeed.companion.discovery.MdnsSource
import io.github.devjardins.sidespeed.companion.discovery.SocketAdbProbe
import io.github.devjardins.sidespeed.companion.discovery.StlsOffer
import io.github.devjardins.sidespeed.companion.discovery.SocketDatagramQuery
import io.github.devjardins.sidespeed.companion.discovery.SsdpSource
import io.github.devjardins.sidespeed.companion.discovery.SweepSource
import io.github.devjardins.sidespeed.companion.discovery.TailscalePeersSource
import io.github.devjardins.sidespeed.companion.net.TrustMatcher
import io.github.devjardins.sidespeed.companion.store.CompanionSettings
import io.github.devjardins.sidespeed.companion.store.JsonFileCompanionStore
import io.github.devjardins.sidespeed.companion.tv.DesktopDirs
import io.github.devjardins.sidespeed.companion.tv.HelperKeeper
import io.github.devjardins.sidespeed.companion.tv.KadbTvConnector
import io.github.devjardins.sidespeed.companion.tv.TvIdentity
import io.github.devjardins.sidespeed.companion.tv.kadbPairer
import io.github.devjardins.sidespeed.companion.net.OverlayTrustMatcher
import io.github.devjardins.sidespeed.companion.discovery.TailscaleStatus
import io.github.devjardins.sidespeed.companion.upkeep.OverlayAccess
import io.github.devjardins.sidespeed.companion.upkeep.ProbeScanner
import io.github.devjardins.sidespeed.companion.upkeep.JoinPoll
import io.github.devjardins.sidespeed.companion.upkeep.Trigger
import io.github.devjardins.sidespeed.companion.upkeep.Upkeep
import io.github.devjardins.sidespeed.companion.resources.Res
import io.github.devjardins.sidespeed.companion.resources.about_download
import io.github.devjardins.sidespeed.companion.resources.app_name
import io.github.devjardins.sidespeed.companion.resources.home_restart_now
import io.github.devjardins.sidespeed.companion.resources.notif_restarted
import io.github.devjardins.sidespeed.companion.resources.tray_open
import io.github.devjardins.sidespeed.companion.resources.tray_pause
import io.github.devjardins.sidespeed.companion.resources.tray_quit
import io.github.devjardins.sidespeed.companion.resources.tray_resume
import io.github.devjardins.sidespeed.companion.ui.CompanionApp
import io.github.devjardins.sidespeed.companion.ui.CompanionViewModel
import io.github.devjardins.sidespeed.companion.ui.SsButton
import io.github.devjardins.sidespeed.companion.ui.text.UiText
import io.github.devjardins.sidespeed.companion.ui.text.resolveSuspend
import io.github.devjardins.sidespeed.companion.update.CompanionUpdateState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource
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
    val identity = TvIdentity.forKeys(DesktopDirs.keyDir())
    // kadb-mdns 2.1.4 has no public way to choose interfaces.
    // KadbMdnsJvm(MdnsConfig) opens one JmDNS per address via createDefaultJmDnsBackends:
    // every up, non-loopback, non-virtual interface, IPv4 and IPv6.
    // MdnsConfig only has serviceTypes and preferIpv4. preferIpv4 is not read when those
    // JmDNS instances are opened. The constructor that takes a backend factory is internal.
    val ports = KadbMdnsConnectPorts(open = {
        KadbMdnsJvm(MdnsConfig(serviceTypes = setOf(MdnsServiceType.TLS_CONNECT)))
    })
    val linkOffer = StlsOffer(SocketAdbProbe())
    val pairer = kadbPairer(DesktopDirs.keyDir()) { store.tvs().map { it.address }.toSet() }
    @OptIn(ExperimentalCoroutinesApi::class)
    val scanner = ProbeScanner(SocketAdbProbe(), Dispatchers.IO.limitedParallelism(Discovery.PARALLELISM))
    val tailscale = TailscaleCli()
    // Desktop only (design decision D1): the tailnet's administrators are trusted to reach the TV.
    val overlay = OverlayAccess({ tailscale.statusJson()?.let(TailscaleStatus::snapshot) }, DesktopEgressCheck())
    val upkeep = Upkeep(
        store, fingerprints, connector, HelperKeeper(), scanner, overlay,
        ports = ports, classicTls = { linkOffer.encryptedAvailable(it) },
        identity = identity,
    ) { System.currentTimeMillis() }
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    // Hints first, so the 4096-probe cap on the sweep can never push them out; the ARP table is read on IO.
    val osName = System.getProperty("os.name").orEmpty().lowercase()
    val arpReader: suspend () -> String = {
        withContext(Dispatchers.IO) {
            when {
                osName.startsWith("mac") || osName.startsWith("darwin") -> runCommand("arp", "-an")
                osName.startsWith("windows") -> runCommand("arp", "-a")
                else -> runCommand("ip", "neigh", "show")
            }
        }
    }
    val arpParser = when {
        osName.startsWith("mac") || osName.startsWith("darwin") -> NeighbourParsers::mac
        osName.startsWith("windows") -> NeighbourParsers::windows
        else -> NeighbourParsers::linux
    }
    @OptIn(ExperimentalCoroutinesApi::class)
    val discovery = Discovery(
        listOf(
            TailscalePeersSource(tailscale::statusJson),
            ArpSource(arpReader, arpParser),
            MdnsSource(SocketDatagramQuery()),
            SsdpSource(SocketDatagramQuery()),
            SweepSource,
        ),
        SocketAdbProbe(),
        Dispatchers.IO.limitedParallelism(Discovery.PARALLELISM),
    )
    val vm = CompanionViewModel(
        store, fingerprints, upkeep, connector, discovery, scope,
        overlay = overlay, pairer = pairer, ports = ports, linkOffer = linkOffer,
        identity = identity,
    )
    val desktopUpdater = DesktopUpdater(CompanionVersion.APP_VERSION, scope = scope)

    application {
        val tray = rememberTrayState()
        val icon = remember { BitmapPainter(useResource("tray.png", ::loadImageBitmap)) }
        val windowIcon = remember { BitmapPainter(useResource("icons/app.png", ::loadImageBitmap)) }
        var windowVisible by remember { mutableStateOf(true) }
        var paused by remember { mutableStateOf(false) }
        val state by vm.state.collectAsState()

        suspend fun runAndNotify(trigger: Trigger) {
            val report = upkeep.runOnce(trigger)
            if (store.settings().notifications && report.restartedNames.isNotEmpty()) {
                val title = UiText.Res(Res.string.app_name).resolveSuspend()
                report.restartedNames.forEach {
                    val body = UiText.Res(Res.string.notif_restarted, listOf(UiText.Ltr(it))).resolveSuspend()
                    tray.sendNotification(Notification(title, body))
                }
            }
            vm.refreshNow()
        }

        LaunchedEffect(Unit) { vm.onOpen() }
        // The activity poller runs only with a visible window: closing it to the tray pauses polls.
        LaunchedEffect(windowVisible) { vm.setForeground(windowVisible) }
        // Check every intervalMinutes, unless paused. Keyed on the interval, so a change restarts the wait.
        LaunchedEffect(state.settings.intervalMinutes) {
            while (true) {
                delay(state.settings.intervalMinutes * 60_000L)
                if (!paused) runAndNotify(Trigger.BACKGROUND)
            }
        }
        // Every 30 s: joining a Trusted Network or a trusted tailnet triggers a check. Nothing is read while
        // paused or before either exists, so the poll spawns no processes then.
        LaunchedEffect(Unit) {
            suspend fun currentOverlayId(): String? {
                val overlays = store.trustedOverlays()
                return if (overlays.isEmpty()) null else OverlayTrustMatcher.currentTrusted(overlay.source.snapshot(), overlays)?.id
            }
            // Seeded with the state at startup: an already-trusted tailnet is not a join on the first tick.
            val poll = JoinPoll(fingerprints.current(), currentOverlayId())
            while (true) {
                delay(30_000)
                if (paused || state.firstRun) continue
                val joined = poll.tick(fingerprints.current(), currentOverlayId()) { fp ->
                    TrustMatcher.currentTrusted(fp, store.trustedNetworks()) != null
                }
                // One run per tick at most, whichever scope was joined.
                if (joined) runAndNotify(Trigger.NETWORK_JOINED)
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
            tooltip = stringResource(Res.string.app_name),
            state = tray,
            onAction = { windowVisible = true },
            menu = {
                Item(stringResource(Res.string.home_restart_now), onClick = { vm.restartNow() })
                Item(stringResource(Res.string.tray_open), onClick = { windowVisible = true })
                Item(
                    stringResource(if (paused) Res.string.tray_resume else Res.string.tray_pause),
                    onClick = { paused = !paused },
                )
                Separator()
                Item(stringResource(Res.string.tray_quit), onClick = { lock.close(); exitApplication() })
            },
        )
        // Closing the window keeps the app in the tray.
        Window(
            onCloseRequest = { windowVisible = false },
            visible = windowVisible,
            title = stringResource(Res.string.app_name),
            icon = windowIcon,
            state = rememberWindowState(size = DpSize(420.dp, 640.dp)),
            resizable = true,
        ) {
            val updateState by desktopUpdater.state.collectAsState()
            CompanionApp(
                vm,
                desktop = true,
                aboutVersion = CompanionVersion.APP_VERSION,
                updateState = updateState,
                onCheckUpdates = desktopUpdater::checkNow,
                updateAction = {
                    if (updateState is CompanionUpdateState.Available) {
                        SsButton(stringResource(Res.string.about_download), onClick = desktopUpdater::openDownload)
                    }
                },
            )
        }
    }
}
