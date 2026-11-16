package io.github.devjardins.sidespeed.companion.ui

import io.github.devjardins.sidespeed.adb.AdbConnectionLost
import io.github.devjardins.sidespeed.companion.discovery.Diagnosis
import io.github.devjardins.sidespeed.companion.discovery.Discovery
import io.github.devjardins.sidespeed.companion.discovery.DiscoveryEvent
import io.github.devjardins.sidespeed.companion.discovery.DiscoveryPlan
import io.github.devjardins.sidespeed.companion.discovery.DiscoverySummary
import io.github.devjardins.sidespeed.companion.discovery.ProbeResult
import io.github.devjardins.sidespeed.companion.discovery.diagnose
import io.github.devjardins.sidespeed.companion.discovery.sweepScope
import io.github.devjardins.sidespeed.companion.net.AddressPolicy
import io.github.devjardins.sidespeed.companion.net.Cidr
import io.github.devjardins.sidespeed.companion.net.FingerprintSource
import io.github.devjardins.sidespeed.companion.net.Ipv4
import io.github.devjardins.sidespeed.companion.net.NetworkFingerprint
import io.github.devjardins.sidespeed.companion.net.OverlayTrustMatcher
import io.github.devjardins.sidespeed.companion.net.TrustMatcher
import io.github.devjardins.sidespeed.companion.net.TrustedNetwork
import io.github.devjardins.sidespeed.companion.net.TrustedOverlay
import io.github.devjardins.sidespeed.companion.store.CompanionSettings
import io.github.devjardins.sidespeed.companion.store.CompanionStore
import io.github.devjardins.sidespeed.companion.store.SavedTv
import io.github.devjardins.sidespeed.companion.store.TvStatus
import io.github.devjardins.sidespeed.companion.tv.TvAuthPending
import io.github.devjardins.sidespeed.companion.tv.TvCommands
import io.github.devjardins.sidespeed.companion.tv.TvConnector
import io.github.devjardins.sidespeed.companion.tv.TvUnreachable
import io.github.devjardins.sidespeed.companion.tv.open
import io.github.devjardins.sidespeed.companion.upkeep.OverlayAccess
import io.github.devjardins.sidespeed.companion.upkeep.OverlayGate
import io.github.devjardins.sidespeed.companion.upkeep.Trigger
import io.github.devjardins.sidespeed.companion.upkeep.Upkeep
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
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
    const val NOT_FIRE_TV_NOTE =
        "Not a Fire TV: SideSpeed on this TV handles setup itself, and the companion can't reach it after restarts."
    const val ALLOW_STEP = "Tick Always allow on your TV"
    const val BAD_ADDRESS = "Enter the TV's address, like 192.168.1.50"
    const val NO_SERIAL = "That device didn't identify itself, so it can't be added"
    const val NO_OVERLAY = "Tailscale isn't running with a network connection, so there is no tailnet to trust"
    const val SHARED_PEER = "That device is shared in from another tailnet, so it can't be added"
    const val CANT_SCAN = "This network can't be scanned, so type the TV's address instead"
    const val OUTSIDE_NETWORK = "That isn't a private or Tailscale address"
    const val UNREACHABLE = "Couldn't reach that address on this network"

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

data class TvCardState(
    val id: String,
    val name: String,
    val address: String,
    val status: TvStatus,
    val statusLabel: String,
    val checkedAtEpochMs: Long?,
    val note: String?,
)

/** What Tailscale reports on this computer: its tailnet, whether it is trusted, and whether it can reach peers. */
data class TailnetState(val name: String, val trusted: Boolean, val ready: Boolean)

data class CompanionUiState(
    val loaded: Boolean = false,
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
) {
    private val overlayGate = overlay?.let(::OverlayGate)

    private val _state = MutableStateFlow(CompanionUiState())
    val state: StateFlow<CompanionUiState> = _state.asStateFlow()
    private val work = Mutex()
    private var findJob: Job? = null
    private var lastSummary = DiscoverySummary()
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
            val existing = store.trustedOverlays()
            if (OverlayTrustMatcher.currentTrusted(snapshot, existing) == null) {
                val id = "overlay-" + clock() + "-" + existing.size
                val label = name.trim().ifEmpty { "Tailnet ${existing.size + 1}" }
                store.saveTrustedOverlay(TrustedOverlay(id, label, snapshot.tailnet, snapshot.selfId))
            }
            refresh()
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
    fun findTvs() = startFind(clearFound = true)

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
        var swept = false
        discovery.run(DiscoveryPlan(sweepScope(subnet, own, gateway), own = own, gateway = gateway, skip = resumeSkip)).collect { event ->
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

    fun removeTv(id: String) {
        locked {
            store.removeTv(id)
            refresh()
        }
    }

    fun updateSettings(s: CompanionSettings) {
        locked {
            store.saveSettings(s)
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
        try {
            upkeep.runOnce(trigger, onlyTvId)
        } finally {
            refresh()
        }
    }

    private suspend fun currentTrusted(): TrustedNetwork? =
        TrustMatcher.currentTrusted(fingerprints.current(), store.trustedNetworks())

    private suspend fun addTv(address: String) {
        if (!isIpv4(address)) return refresh(error = Copy.BAD_ADDRESS)
        val ip = Ipv4.parse(address)
        // A tailnet peer's address binds the TV to that peer on the trusted tailnet, which also works away from home.
        val binding = ip?.let { overlayGate?.bindingFor(it, store.trustedOverlays()) }
        // A node shared in from another tailnet is never trusted, not even through the home network (design section 6).
        if (binding == null && ip != null && AddressPolicy.isOverlay(ip) &&
            overlay?.source?.snapshot()?.peers?.any { it.shared && ip in it.ips } == true
        ) return refresh(error = Copy.SHARED_PEER)
        val trusted = if (binding == null) currentTrusted() ?: return refresh(error = Copy.NOT_TRUSTED_ERROR) else null
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
        if (!stillHere) return refresh(error = Copy.NOT_TRUSTED_ERROR)
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

    private suspend fun refresh(error: String? = null, settleBusy: Boolean = true) {
        val fp = fingerprints.current()
        val networks = store.trustedNetworks()
        val trusted = TrustMatcher.currentTrusted(fp, networks)
        val overlays = store.trustedOverlays()
        val tailnet = overlay?.source?.snapshot()?.let { snap ->
            TailnetState(snap.tailnet, overlays.any { o -> OverlayTrustMatcher.matches(snap, o) }, snap.running && snap.tun)
        }
        val tvs = store.tvs()
        val saved = tvs.map { it.address }.toSet()
        val cards = tvs.map { tv ->
            TvCardState(
                id = tv.id,
                name = tv.name,
                address = tv.address,
                status = tv.status,
                statusLabel = Copy.statusLabel(tv.status),
                checkedAtEpochMs = tv.lastCheckedEpochMs,
                note = if (tv.isFireTv) null else Copy.NOT_FIRE_TV_NOTE,
            )
        }
        _state.update {
            it.copy(
                loaded = true,
                firstRun = networks.isEmpty() && overlays.isEmpty(),
                currentNetwork = fp,
                trustedNetworkName = trusted?.displayName,
                trustedNetworks = networks,
                trustedOverlays = overlays,
                tailnet = tailnet,
                tvs = cards,
                settings = store.settings(),
                busy = if (settleBusy) false else it.busy,
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
    }
}
