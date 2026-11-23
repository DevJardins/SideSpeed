package io.github.devjardins.sidespeed.companion.ui

import io.github.devjardins.sidespeed.adb.AdbConnectionLost
import io.github.devjardins.sidespeed.companion.discovery.Diagnosis
import io.github.devjardins.sidespeed.companion.discovery.Discovery
import io.github.devjardins.sidespeed.companion.discovery.DiscoveryEvent
import io.github.devjardins.sidespeed.companion.discovery.DiscoveryPlan
import io.github.devjardins.sidespeed.companion.discovery.DiscoverySummary
import io.github.devjardins.sidespeed.companion.discovery.ProbeResult
import io.github.devjardins.sidespeed.companion.discovery.RangeChoice
import io.github.devjardins.sidespeed.companion.discovery.diagnose
import io.github.devjardins.sidespeed.companion.discovery.scanRangeFor
import io.github.devjardins.sidespeed.companion.discovery.sweepScope
import io.github.devjardins.sidespeed.companion.net.AddressPolicy
import io.github.devjardins.sidespeed.companion.net.Cidr
import io.github.devjardins.sidespeed.companion.net.FingerprintSource
import io.github.devjardins.sidespeed.companion.net.Ipv4
import io.github.devjardins.sidespeed.companion.net.NetworkFingerprint
import io.github.devjardins.sidespeed.companion.net.OverlaySnapshot
import io.github.devjardins.sidespeed.companion.net.OverlayTrustMatcher
import io.github.devjardins.sidespeed.companion.net.TrustMatcher
import io.github.devjardins.sidespeed.companion.net.TrustedNetwork
import io.github.devjardins.sidespeed.companion.net.TrustedOverlay
import io.github.devjardins.sidespeed.companion.store.CompanionSettings
import io.github.devjardins.sidespeed.companion.store.CompanionStore
import io.github.devjardins.sidespeed.companion.store.Reach
import io.github.devjardins.sidespeed.companion.store.SavedTv
import io.github.devjardins.sidespeed.companion.store.TvStatus
import io.github.devjardins.sidespeed.companion.tv.TvActivity
import io.github.devjardins.sidespeed.companion.tv.TvAuthPending
import io.github.devjardins.sidespeed.companion.tv.TvCommands
import io.github.devjardins.sidespeed.companion.tv.TvConnector
import io.github.devjardins.sidespeed.companion.tv.TvSession
import io.github.devjardins.sidespeed.companion.tv.TvUnreachable
import io.github.devjardins.sidespeed.companion.tv.open
import io.github.devjardins.sidespeed.companion.tv.parseActivityRow
import io.github.devjardins.sidespeed.companion.upkeep.OverlayAccess
import io.github.devjardins.sidespeed.companion.upkeep.OverlayGate
import io.github.devjardins.sidespeed.companion.upkeep.OverlayVerdict
import io.github.devjardins.sidespeed.companion.upkeep.Trigger
import io.github.devjardins.sidespeed.companion.upkeep.Upkeep
import io.github.devjardins.sidespeed.companion.upkeep.UpkeepReport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

object Copy {
    /** Shown under a TV; the screen re-evaluates it on a timer so it never freezes. */
    fun checkedAgo(diffMs: Long): String = "Checked ${(diffMs / 60_000).coerceAtLeast(0)} min ago"

    const val NO_TVS_FOUND = "No TVs found"
    const val TLS_UNSUPPORTED = "needs TLS pairing, not supported yet"
    const val NOT_TRUSTED_ERROR = "Join a Trusted Network to find TVs"
    const val NOT_TRUSTED_ADD = "Join a Trusted Network to add a TV"
    const val NOT_FIRE_TV_NOTE =
        "Not a Fire TV: SideSpeed on this TV handles setup itself, and the companion can't reach it after restarts. " +
            "On Google TV the helper is optional; Home shows what's compiling."
    const val ALLOW_STEP = "Tick Always allow on your TV"
    const val BAD_ADDRESS = "Enter the TV's address, like 192.168.1.50"
    const val NO_SERIAL = "That device didn't identify itself, so it can't be added"
    const val NO_OVERLAY = "Tailscale isn't running with a network connection, so there is no tailnet to trust"
    const val SHARED_PEER = "That device is shared in from another tailnet, so it can't be added"
    const val CANT_SCAN = "This network can't be scanned, so type the TV's address instead"
    const val OUTSIDE_NETWORK = "That isn't a private or Tailscale address"
    const val UNREACHABLE = "Couldn't reach that address on this network"
    const val WIFI_NAME_TITLE = "Use the Wi-Fi name to recognise this network"
    const val WIFI_NAME_CAVEAT =
        "Android calls this location access. " +
            "SideSpeed only reads the Wi-Fi name and access point. It stays on this device."
    const val WIFI_NAME_OFF =
        "SideSpeed recognises this network by its router. " +
            "Turning on the Wi-Fi name makes that harder to fake."
    const val WIFI_NAME_REVOKE = "To stop it fully, turn off location access for SideSpeed in Android settings."
    const val BAD_RANGE = "Enter a range on this network, like 192.168.1.0/24"
    const val NOT_CHECKED_HERE = "Not checked: not on its network"
    const val OLDER_COPY =
        "This copy of SideSpeed Companion is older than the one that saved your TVs. " +
            "Changes won't be saved; open the newest copy instead."

    fun keptAliveWhileOn(network: String) = "Kept alive while on $network"

    /** A saved status shown where the TV can't be checked from here: it may be stale. */
    fun lastSeen(label: String): String = "Last seen: $label"

    fun statusLabel(s: TvStatus): String = when (s) {
        TvStatus.RUNNING -> "Helper running"
        TvStatus.NOT_RUNNING -> "Not running"
        TvStatus.OFFLINE -> "Offline"
        TvStatus.WAITING_FOR_ALLOW -> "Waiting for you to allow"
        TvStatus.NOT_INSTALLED -> "SideSpeed isn't installed"
        TvStatus.WRONG_DEVICE -> "Different device at this address"
        TvStatus.START_FAILED -> "Couldn't start the helper"
        TvStatus.UNKNOWN -> "Not checked yet"
    }
}

/** How a Restart-now press ended for one TV: reached while running, reached while not running, or never contacted. */
enum class RestartOutcome { RESTARTED, CHECKED, NOT_CHECKED }

/** The outcome of the latest Restart-now press for [tvId]; cleared when the screen has shown it. */
data class RestartResult(val tvId: String, val outcome: RestartOutcome)

data class TvCardState(
    val id: String,
    val name: String,
    val address: String,
    val status: TvStatus,
    val statusLabel: String,
    /** Whether upkeep could contact this TV right now; when false the card shows the saved status as last-seen. */
    val checkableHere: Boolean,
    val checkedAtEpochMs: Long?,
    val note: String?,
    val scopeNote: String? = null,
    /** How the last check reached this TV; null when never checked, or when it can't be checked from here. */
    val reach: Reach? = null,
    /** The latest live compile status, while the Home screen polls for it; null when unknown. */
    val activity: TvActivity? = null,
)

/** What Tailscale reports on this computer: its tailnet, whether it is trusted, and whether it can reach peers. */
data class TailnetState(val name: String, val trusted: Boolean, val ready: Boolean)

data class CompanionUiState(
    val loaded: Boolean = false,
    /**
     * The store file was saved by a newer copy of the companion: the store is read-only and Home shows
     * a persistent banner ([Copy.OLDER_COPY]) instead of the edits it drops.
     */
    val savedByNewerVersion: Boolean = false,
    /** No Trusted Network chosen yet: the first-run screen shows and nothing touches the network. */
    val firstRun: Boolean = true,
    val currentNetwork: NetworkFingerprint? = null,
    val trustedNetworkName: String? = null,
    val trustedNetworks: List<TrustedNetwork> = emptyList(),
    val trustedOverlays: List<TrustedOverlay> = emptyList(),
    /** Null when there is no Tailscale to ask (Android, not installed, logged out). */
    val tailnet: TailnetState? = null,
    val tvs: List<TvCardState> = emptyList(),
    val settings: CompanionSettings = CompanionSettings(),
    val busy: Boolean = false,
    val lastRestart: RestartResult? = null,
    val error: String? = null,
    /** Addresses of TVs found by "Find TVs" that aren't saved yet. */
    val found: List<String> = emptyList(),
    /** Addresses that answer ADB over TLS: listed, but not offered as addable TVs. */
    val unsupported: List<String> = emptyList(),
    /** Addresses whose add is in progress: their rows show "Adding…" and ignore presses. */
    val adding: Set<String> = emptyList<String>().toSet(),
    /** Per-address add failures, shown on the row that was pressed. */
    val addErrors: Map<String, String> = emptyMap(),
    /** The address of the TV just added; the Add screen returns Home on it (see [CompanionViewModel.acknowledgeAdded]). */
    val addedAddress: String? = null,
    /** A Find TVs scan is running: the button shows a spinner and ignores presses. */
    val scanning: Boolean = false,
    /** The last scan finished with no new TVs. */
    val noneFound: Boolean = false,
    /** "Scanned N of M" for the running or latest scan; null before the first one. */
    val scanProgress: String? = null,
    /** The latest scan finished but the probe cap cut it short: the Add screen offers "Keep scanning". */
    val scanTruncated: Boolean = false,
    /** The latest scan was stopped before it finished: the Add screen offers "Keep scanning". */
    val scanStopped: Boolean = false,
    /** What the latest finished or stopped scan concluded; null before the first one. */
    val diagnosis: Diagnosis? = null,
)

class CompanionViewModel(
    private val store: CompanionStore,
    private val fingerprints: FingerprintSource,
    private val upkeep: Upkeep,
    private val connector: TvConnector,
    private val discovery: Discovery,
    private val scope: CoroutineScope,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val idGenerator: () -> String = { "tv-" + clock() + "-" + (counter++) },
    private val overlay: OverlayAccess? = null,
    val wifiName: WifiNameAccess = WifiNameAccess.Unsupported,
) {
    private val overlayGate = overlay?.let(::OverlayGate)

    private val _state = MutableStateFlow(CompanionUiState())
    val state: StateFlow<CompanionUiState> = _state.asStateFlow()
    private var homeVisible = false
    private var foreground = true
    private var pollJob: Job? = null
    private val activities = mutableMapOf<String, TvActivity>()
    private val pollSessions = mutableMapOf<String, TvSession>()
    private val pollFailures = mutableMapOf<String, Int>()
    private val parkedTvIds = mutableSetOf<String>()
    private val work = Mutex()
    private var findJob: Job? = null
    private var lastSummary = DiscoverySummary()
    /** The range the current sweep is limited to; blank is the automatic one. Keep scanning reuses it. */
    private var findRange = ""
    /** Every host probed across the runs since the last fresh Find TVs, so Keep scanning resumes past them. */
    private val probedSinceFind = HashSet<Ipv4>()

    /** Hosts that answered without speaking ADB since the last fresh Find TVs, kept across a stop and resume. */
    private val liveNonAdbSinceFind = HashSet<Ipv4>()

    init {
        scope.launch { refresh() }
    }

    fun onOpen() {
        locked {
            val anyScope = currentTrusted() != null || (overlay != null && store.trustedOverlays().isNotEmpty())
            if (anyScope) runUpkeep(Trigger.APP_OPEN) else refresh()
        }
    }

    fun trustCurrentNetwork(name: String) {
        locked {
            val fp = fingerprints.current()
            val clean = name.trim()
            if (fp == null) return@locked refresh(error = "No network to trust")
            val existing = store.trustedNetworks()
            // Already trusted: keep that entry untouched (name and fingerprint) rather than rewriting it.
            if (TrustMatcher.currentTrusted(fp, existing) == null) {
                val id = "net-" + clock() + "-" + existing.size
                store.saveTrustedNetwork(TrustedNetwork(id, clean.ifEmpty { "Trusted Network ${existing.size + 1}" }, fp))
            }
            refresh()
        }
    }

    /** Trusts the tailnet this computer is on now, so TVs bound to it are kept alive wherever Tailscale is up. */
    fun trustCurrentOverlay(name: String) {
        locked {
            val snapshot = overlay?.source?.snapshot()
            if (snapshot == null || !snapshot.running || !snapshot.tun) return@locked refresh(error = Copy.NO_OVERLAY)
            trustSnapshot(snapshot, name)
            refresh()
        }
    }

    /** Saves the tailnet [snapshot] reports as trusted, unless it already is. The add path uses this too: the
     * tailnet the computer is signed into is trusted automatically when a TV is added on it. */
    private suspend fun trustSnapshot(snapshot: OverlaySnapshot, name: String) {
        val existing = store.trustedOverlays()
        if (OverlayTrustMatcher.currentTrusted(snapshot, existing) == null) {
            val id = "overlay-" + clock() + "-" + existing.size
            val label = name.trim().ifEmpty { "Tailnet ${existing.size + 1}" }
            store.saveTrustedOverlay(TrustedOverlay(id, label, snapshot.tailnet, snapshot.selfId))
        }
    }

    /** Removes the tailnet and the TVs bound to it: upkeep would never check them again. */
    fun removeTrustedOverlay(id: String) {
        locked {
            store.tvs().filter { it.trustedNetworkId == id }.forEach { store.removeTv(it.id) }
            store.removeTrustedOverlay(id)
            refresh()
        }
    }

    /** Sets the user's label for network [id]; a blank label clears it (the identifier is shown instead). */
    fun renameTrustedNetwork(id: String, label: String) {
        locked {
            store.trustedNetworks().firstOrNull { it.id == id }?.let { store.saveTrustedNetwork(it.copy(name = label.trim())) }
            refresh()
        }
    }

    /** Removes the network and the TVs saved under it: upkeep would never check them again. */
    fun removeTrustedNetwork(id: String) {
        locked {
            store.tvs().filter { it.trustedNetworkId == id }.forEach { store.removeTv(it.id) }
            store.removeTrustedNetwork(id)
            refresh()
        }
    }

    /**
     * Adds the TV at [address]. A press while that address is being added, or right after it was added, does
     * nothing. Success sets [CompanionUiState.addedAddress]; failure sets [CompanionUiState.addErrors] for it.
     */
    fun addTvByAddress(address: String) {
        val a = address.trim()
        var accepted = false
        _state.update {
            accepted = a !in it.adding && it.addedAddress != a
            if (accepted) it.copy(adding = it.adding + a, addErrors = it.addErrors - a) else it
        }
        if (!accepted) return
        locked {
            try {
                addTv(a)
                val failure = _state.value.error
                _state.update {
                    if (failure != null) it.copy(addErrors = it.addErrors + (a to failure)) else it.copy(addedAddress = a)
                }
            } finally {
                _state.update { it.copy(adding = it.adding - a) }
            }
        }
    }

    /** The Add screen has shown the result of [CompanionUiState.addedAddress]. */
    fun acknowledgeAdded() {
        _state.update { it.copy(addedAddress = null) }
    }

    /**
     * Sweeps the Trusted Network for TVs, streaming results as they arrive. Runs in its own job outside the
     * [work] mutex, so adding a TV or running upkeep never waits for the sweep. A press while scanning, or a
     * second [keepScanning], does nothing. Clears the previous results first; [keepScanning] keeps them.
     */
    fun findTvs() = findTvsIn("")

    /** Sweeps the Trusted Network, limited to [range] when it names a block on it; blank is automatic. */
    fun findTvsIn(range: String) {
        findRange = range
        startFind(clearFound = true)
    }

    /** Re-sweeps without clearing what the stopped scan already found. */
    fun keepScanning() = startFind(clearFound = false)

    /** Stops the running sweep, keeping whatever it streamed so far. */
    fun cancelFind() {
        findJob?.cancel()
    }

    /** The Add screen went away: a running sweep stops with it. */
    fun onDispose() {
        findJob?.cancel()
    }

    private fun startFind(clearFound: Boolean) {
        var accepted = false
        _state.update {
            accepted = !it.scanning
            if (!accepted) it else it.copy(
                scanning = true,
                noneFound = false,
                found = if (clearFound) emptyList() else it.found,
                unsupported = if (clearFound) emptyList() else it.unsupported,
                scanProgress = null,
                scanStopped = false,
                scanTruncated = false,
                diagnosis = null,
            )
        }
        if (!accepted) return
        findJob?.cancel()
        // A run that finished has stopped touching these sets before it cleared `scanning`.
        val resumeSkip = if (clearFound) emptySet() else probedSinceFind.toSet()
        if (clearFound) {
            probedSinceFind.clear()
            liveNonAdbSinceFind.clear()
        }
        lastSummary = DiscoverySummary()
        findJob = scope.launch {
            var cancelled = false
            var swept = false
            try {
                swept = collectFind(resumeSkip)
            } catch (e: CancellationException) {
                cancelled = true
                throw e
            } finally {
                _state.update {
                    it.copy(
                        scanning = false,
                        scanStopped = cancelled,
                        scanTruncated = !cancelled && lastSummary.truncated,
                        // A run that never swept (no network, plan refused) has nothing to diagnose.
                        diagnosis = if (!swept && !cancelled) null else diagnose(
                            lastSummary.copy(liveNonAdb = liveNonAdbSinceFind.size),
                            found = it.found.isNotEmpty(),
                            complete = !cancelled,
                            tlsOnly = it.found.isEmpty() && it.unsupported.isNotEmpty(),
                        ),
                    )
                }
            }
        }
    }

    /** Returns whether a sweep ran to its end. Its refreshes leave `busy` alone: an add or upkeep may still be running. */
    private suspend fun collectFind(resumeSkip: Set<Ipv4>): Boolean {
        val trusted = currentTrusted()
        val subnet = trusted?.let { Cidr.parse(it.fingerprint.subnet) }
        if (trusted == null || subnet == null) {
            refresh(error = Copy.NOT_TRUSTED_ERROR, settleBusy = false)
            return false
        }
        val saved = store.tvs().map { it.address }.toSet()
        // The live address, not the saved one: DHCP moves it, and networks saved before ownIp existed have none.
        val own = fingerprints.current()?.ownIp?.let(Ipv4::parse) ?: trusted.fingerprint.ownIp?.let(Ipv4::parse)
        val gateway = Ipv4.parse(trusted.fingerprint.gatewayIp)
        val scope = when (val c = scanRangeFor(findRange, trusted)) {
            RangeChoice.Invalid -> {
                refresh(error = Copy.BAD_RANGE, settleBusy = false)
                return false
            }
            is RangeChoice.Custom -> c.cidr
            RangeChoice.Default -> sweepScope(subnet, own, gateway)
        }
        var swept = false
        discovery.run(DiscoveryPlan(scope, own = own, gateway = gateway, skip = resumeSkip)).collect { event ->
            when (event) {
                is DiscoveryEvent.Progress ->
                    _state.update { it.copy(scanProgress = "Scanned ${event.scanned} of ${event.total}") }
                is DiscoveryEvent.Found -> {
                    val host = event.address.toString()
                    _state.update { s ->
                        if (host in saved || host in s.found || host in s.unsupported) s
                        else if (event.result == ProbeResult.AdbTls) s.copy(unsupported = s.unsupported + host)
                        else s.copy(found = s.found + host)
                    }
                }
                is DiscoveryEvent.Probed -> {
                    probedSinceFind += event.address
                    if (!event.result.isAdb) liveNonAdbSinceFind += event.address
                }
                is DiscoveryEvent.Done -> {
                    lastSummary = event.summary
                    swept = true
                }
            }
        }
        refresh(error = if (swept) null else Copy.CANT_SCAN, settleBusy = false)
        if (swept) _state.update { it.copy(noneFound = it.found.isEmpty()) }
        return swept
    }

    /** Checks only [tvId] (or every TV on this Trusted Network when null) and clears its "waiting for Allow" skip. */
    fun restartNow(tvId: String? = null) {
        locked {
            if (tvId != null) store.updateTv(tvId) { it.copy(skipBackgroundUntilUserAction = false) }
            runUpkeep(Trigger.RESTART_NOW, tvId)
        }
    }

    /** The restart message has been shown (or there is nothing to show): clear it. */
    fun acknowledgeRestart() {
        _state.update { it.copy(lastRestart = null) }
    }

    fun removeTv(id: String) {
        locked {
            store.removeTv(id)
            refresh()
        }
    }

    /**
     * The Home screen appeared (true) or went away (false). Polling runs only while it is visible
     * and the app is in the foreground (see [setForeground]): one pass over the checkable, running
     * TVs every [ACTIVITY_POLL_MS], reusing one ADB session per TV. Three consecutive failures park
     * a TV until the next visit; unparsable output keeps the previous state. This never runs from
     * background upkeep: upkeep never calls it.
     */
    fun setHomeVisible(visible: Boolean) {
        if (visible) parkedTvIds.clear()
        if (homeVisible == visible) return
        homeVisible = visible
        updatePolling()
    }

    /** The app moved to the foreground (true) or background (false). */
    fun setForeground(inForeground: Boolean) {
        if (foreground == inForeground) return
        foreground = inForeground
        updatePolling()
    }

    private fun updatePolling() {
        pollJob?.cancel()
        pollJob = null
        pollSessions.values.forEach { runCatching { it.close() } }
        pollSessions.clear()
        if (!homeVisible || !foreground) return
        pollJob = scope.launch { pollLoop() }
    }

    private suspend fun pollLoop() {
        while (true) {
            currentCoroutineContext().ensureActive()
            val eligible = _state.value.tvs.filter {
                it.checkableHere && it.status == TvStatus.RUNNING && it.id !in parkedTvIds
            }
            val ids = eligible.map { it.id }.toSet()
            (pollSessions.keys - ids).forEach { id -> runCatching { pollSessions.remove(id)?.close() } }
            for (tv in eligible) {
                currentCoroutineContext().ensureActive()
                pollOneTv(tv)
            }
            delay(ACTIVITY_POLL_MS)
        }
    }

    private suspend fun pollOneTv(tv: TvCardState) {
        try {
            val session = pollSessions.getOrPut(tv.id) { connector.open(tv.address) }
            val parsed = parseActivityRow(session.run(TvCommands.STATUS_QUERY))
            pollFailures.remove(tv.id)
            if (parsed != null) setActivity(tv.id, parsed)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            runCatching { pollSessions.remove(tv.id)?.close() }
            val count = (pollFailures[tv.id] ?: 0) + 1
            if (count >= MAX_ACTIVITY_FAILURES) {
                pollFailures.remove(tv.id)
                parkedTvIds += tv.id
            } else {
                pollFailures[tv.id] = count
            }
        }
    }

    private fun setActivity(id: String, activity: TvActivity) {
        activities[id] = activity
        _state.update { s -> s.copy(tvs = s.tvs.map { if (it.id == id) it.copy(activity = activity) else it }) }
    }

    fun updateSettings(s: CompanionSettings) {
        locked {
            store.saveSettings(s)
            refresh()
        }
    }

    /**
     * Turns the Wi-Fi name on (asking for location permission first) or off. Turning on without the
     * permission leaves the switch off; turning off keeps the Android permission, which the user removes
     * in system settings (Settings says so).
     */
    fun setUseWifiName(enabled: Boolean) {
        locked {
            val s = store.settings()
            if (!enabled) {
                if (s.useWifiName) store.saveSettings(s.copy(useWifiName = false))
                refresh()
                return@locked
            }
            if (wifiName.supported && wifiName.request()) store.saveSettings(s.copy(useWifiName = true))
            refresh()
        }
    }

    /** Re-reads the store and network into [state], for callers that ran upkeep themselves (the tray timers). */
    fun refreshNow() {
        locked { refresh() }
    }

    /** Runs [block] one at a time, so busy and error state can't be clobbered by overlapping actions. */
    private fun locked(block: suspend () -> Unit) {
        scope.launch { work.withLock { block() } }
    }

    private suspend fun runUpkeep(trigger: Trigger, onlyTvId: String? = null) {
        _state.update { it.copy(busy = true) }
        var report: UpkeepReport? = null
        try {
            report = upkeep.runOnce(trigger, onlyTvId)
        } finally {
            val finished = report
            if (onlyTvId == null || finished == null) {
                // A throw still ends busy, but without a report there is no restart outcome to record.
                refresh()
            } else {
                // The outcome lands in the same state update that ends busy,
                // so the screen never sees one without the other.
                val status = finished.visited[onlyTvId]
                val outcome = when {
                    status == TvStatus.RUNNING -> RestartOutcome.RESTARTED
                    status != null -> RestartOutcome.CHECKED
                    else -> RestartOutcome.NOT_CHECKED
                }
                refresh(lastRestart = RestartResult(onlyTvId, outcome), updateLastRestart = true)
            }
        }
    }

    private suspend fun currentTrusted(): TrustedNetwork? =
        TrustMatcher.currentTrusted(fingerprints.current(), store.trustedNetworks())

    /**
     * Whether upkeep could contact [tv] right now: a LAN TV when the current Trusted Network is the one it is
     * saved under; a tailnet TV when the overlay gate upkeep checks before a visit would let it through.
     */
    private suspend fun checkableHere(tv: SavedTv, trusted: TrustedNetwork?, overlays: List<TrustedOverlay>): Boolean {
        if (tv.overlayPeerId == null) return trusted?.id == tv.trustedNetworkId
        val gate = overlayGate ?: return false
        val bound = overlays.firstOrNull { it.id == tv.trustedNetworkId } ?: return false
        return gate.verdict(bound, tv) is OverlayVerdict.Open
    }

    private suspend fun addTv(address: String) {
        if (!isIpv4(address)) return refresh(error = Copy.BAD_ADDRESS)
        val ip = Ipv4.parse(address)
        // A tailnet peer's address binds the TV to that peer on the trusted tailnet, which also works away from home.
        var binding = ip?.let { overlayGate?.bindingFor(it, store.trustedOverlays()) }
        // A node shared in from another tailnet is never trusted, not even through the home network (design section 6).
        if (binding == null && ip != null && AddressPolicy.isOverlay(ip) &&
            overlay?.source?.snapshot()?.peers?.any { it.shared && ip in it.ips } == true
        ) return refresh(error = Copy.SHARED_PEER)
        if (binding == null && ip != null && overlayGate != null) {
            // Our own tailnet is trusted automatically when a TV is added on it.
            val current = overlayGate.peerOnCurrentTailnet(ip)
            if (current != null) {
                trustSnapshot(current.snapshot, "Tailnet ${store.trustedOverlays().size + 1}")
                binding = overlayGate.bindingFor(ip, store.trustedOverlays())
            }
        }
        val trusted = if (binding == null) currentTrusted() ?: return refresh(error = Copy.NOT_TRUSTED_ADD) else null
        // While on a Trusted Network any private (RFC 1918) or overlay (100.64.0.0/10) address may be tried; public addresses never are.
        if (ip == null || !AddressPolicy.contactable(ip)) return refresh(error = Copy.OUTSIDE_NETWORK)
        val scopeId = binding?.overlay?.id ?: trusted!!.id
        _state.update { it.copy(busy = true, error = null) }
        val identity = try {
            connector.open(address).use { session ->
                // Fixed identity reads only; the serial is what upkeep later checks before anything else.
                Triple(
                    session.run(TvCommands.SERIAL),
                    session.run(TvCommands.MODEL),
                    session.run(TvCommands.MANUFACTURER),
                )
            }
        } catch (e: TvAuthPending) {
            return refresh(error = Copy.ALLOW_STEP)
        } catch (e: TvUnreachable) {
            return refresh(error = Copy.UNREACHABLE)
        } catch (e: AdbConnectionLost) {
            return refresh(error = Copy.UNREACHABLE)
        }
        val (serial, model, maker) = identity
        // The network may have changed during the connect: don't record a TV for a network we've left.
        val stillHere = if (binding != null) {
            overlayGate?.bindingFor(ip, store.trustedOverlays())
                ?.let { it.overlay.id == binding.overlay.id && it.peer.id == binding.peer.id } == true
        } else {
            currentTrusted()?.id == scopeId
        }
        if (!stillHere) return refresh(error = Copy.NOT_TRUSTED_ADD)
        if (serial.isBlank()) return refresh(error = Copy.NO_SERIAL)
        // Same TV: same scope, or any LAN entry when this add is a LAN one (a TV moved between Trusted Networks).
        val existing = store.tvs().firstOrNull {
            it.serial == serial && (it.trustedNetworkId == scopeId || (binding == null && it.overlayPeerId == null))
        }
        store.saveTv(
            SavedTv(
                id = existing?.id ?: idGenerator(),
                name = model.ifBlank { "TV at $address" },
                address = address,
                serial = serial,
                trustedNetworkId = scopeId,
                overlayPeerId = binding?.peer?.id,
                isFireTv = maker.equals("Amazon", ignoreCase = true),
            ),
        )
        runUpkeep(Trigger.APP_OPEN)
    }

    private suspend fun refresh(
        error: String? = null,
        settleBusy: Boolean = true,
        lastRestart: RestartResult? = null,
        updateLastRestart: Boolean = false,
    ) {
        val fp = fingerprints.current()
        val networks = store.trustedNetworks()
        val matched = TrustMatcher.currentTrusted(fp, networks)
        // A match on a record made before the gateway MAC or Wi-Fi name was read hardens that record.
        val upgraded = if (fp != null && matched != null) TrustMatcher.upgraded(matched, fp) else null
        if (upgraded != null) store.saveTrustedNetwork(upgraded)
        val trusted = upgraded ?: matched
        // The upgrade above rewrote the record, so the state reads the saved copy.
        val networksNow = if (upgraded != null) store.trustedNetworks() else networks
        val overlays = store.trustedOverlays()
        val tailnet = overlay?.source?.snapshot()?.let { snap ->
            TailnetState(snap.tailnet, overlays.any { o -> OverlayTrustMatcher.matches(snap, o) }, snap.running && snap.tun)
        }
        val tvs = store.tvs()
        activities.keys.retainAll(tvs.map { it.id }.toSet())
        val saved = tvs.map { it.address }.toSet()
        val cards = ArrayList<TvCardState>(tvs.size)
        for (tv in tvs) {
            val here = checkableHere(tv, trusted, overlays)
            cards += TvCardState(
                id = tv.id,
                name = tv.name,
                address = tv.address,
                status = tv.status,
                statusLabel = Copy.statusLabel(tv.status),
                checkableHere = here,
                activity = activities[tv.id],
                reach = if (here) tv.reach else null,
                checkedAtEpochMs = tv.lastCheckedEpochMs,
                note = if (tv.isFireTv) null else Copy.NOT_FIRE_TV_NOTE,
                scopeNote = if (tv.overlayPeerId == null && Ipv4.parse(tv.address)?.let(AddressPolicy::isOverlay) == true)
                    networks.firstOrNull { it.id == tv.trustedNetworkId }?.displayName?.let(Copy::keptAliveWhileOn)
                else null,
            )
        }
        _state.update {
            it.copy(
                loaded = true,
                savedByNewerVersion = store.savedByNewerVersion,
                firstRun = networks.isEmpty() && overlays.isEmpty(),
                currentNetwork = fp,
                trustedNetworkName = trusted?.displayName,
                trustedNetworks = networksNow,
                trustedOverlays = overlays,
                tailnet = tailnet,
                tvs = cards,
                settings = store.settings(),
                busy = if (settleBusy) false else it.busy,
                lastRestart = if (updateLastRestart) lastRestart else it.lastRestart,
                error = error,
                found = if (trusted == null) emptyList() else it.found.filter { h -> h !in saved },
            )
        }
    }

    private fun isIpv4(s: String): Boolean {
        val p = s.split('.')
        return p.size == 4 && p.all { o -> o.isNotEmpty() && o.length <= 3 && o.all { it in '0'..'9' } && o.toInt() <= 255 }
    }

    private companion object {
        var counter = 0
        /** Home-screen activity polls run this often, one TV at a time, reusing each TV's session. */
        const val ACTIVITY_POLL_MS = 3_000L
        /** Consecutive poll failures that park a TV until the next screen visit. */
        const val MAX_ACTIVITY_FAILURES = 3
    }
}
