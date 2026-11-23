package io.github.devjardins.sidespeed.companion.ui

import io.github.devjardins.sidespeed.adb.AdbConnectionLost
import io.github.devjardins.sidespeed.companion.discovery.Diagnosis
import io.github.devjardins.sidespeed.companion.discovery.Discovery
import io.github.devjardins.sidespeed.companion.discovery.DiscoveryEvent
import io.github.devjardins.sidespeed.companion.discovery.DiscoveryPlan
import io.github.devjardins.sidespeed.companion.discovery.DiscoverySummary
import io.github.devjardins.sidespeed.companion.discovery.ConnectPortFinder
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
import io.github.devjardins.sidespeed.companion.store.TvLink
import io.github.devjardins.sidespeed.companion.store.TvStatus
import io.github.devjardins.sidespeed.companion.tv.ClassicNotAcknowledged
import io.github.devjardins.sidespeed.companion.tv.EncryptedAvailable
import io.github.devjardins.sidespeed.companion.tv.EncryptedPortUnknown
import io.github.devjardins.sidespeed.companion.tv.LinkOffer
import io.github.devjardins.sidespeed.companion.tv.PairCopy
import io.github.devjardins.sidespeed.companion.tv.PairResult
import io.github.devjardins.sidespeed.companion.tv.Pairer
import io.github.devjardins.sidespeed.companion.tv.TvActivity
import io.github.devjardins.sidespeed.companion.tv.TvAuthPending
import io.github.devjardins.sidespeed.companion.tv.TvCommands
import io.github.devjardins.sidespeed.companion.tv.TvConnector
import io.github.devjardins.sidespeed.companion.tv.TvSession
import io.github.devjardins.sidespeed.companion.tv.TvSessions
import io.github.devjardins.sidespeed.companion.tv.TvTarget
import io.github.devjardins.sidespeed.companion.tv.TvUnreachable
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
    const val NOT_TRUSTED_ERROR = "Join a Trusted Network to find TVs"
    const val NOT_TRUSTED_ADD = "Join a Trusted Network to add a TV"
    const val NOT_FIRE_TV_NOTE =
        "Not a Fire TV: SideSpeed on this TV handles setup itself, and the companion can't reach it after restarts. " +
            "On Google TV the helper is optional; Home shows what's compiling."
    const val ALLOW_STEP = "Tick Always allow on your TV"
    const val BAD_ADDRESS = "Enter the TV's address, like 192.168.1.50"
    const val NO_SERIAL = "That device didn't identify itself, so it can't be added"
    const val DIFFERENT_DEVICE = "A different device answered at that address."
    const val NO_OVERLAY = "Tailscale isn't running with a network connection, so there is no tailnet to trust"
    const val SHARED_PEER = "That device is shared in from another tailnet, so it can't be added"
    const val CANT_SCAN = "This network can't be scanned, so type the TV's address instead"
    const val OUTSIDE_NETWORK = "That isn't a private or Tailscale address"
    const val UNREACHABLE = "Couldn't reach that address on this network"
    const val BAD_RANGE = "Enter a range on this network, like 192.168.1.0/24"
    const val NOT_CHECKED_HERE = "Not checked: not on its network"
    const val OLDER_COPY =
        "This copy of SideSpeed Companion is older than the one that saved your TVs. " +
            "Changes won't be saved; open the newest copy instead."
    const val PAIR_STEPS =
        "On the TV, open Settings → Developer options → Wireless debugging → Pair device with pairing code."
    const val PAIR_CODE = "Pairing code"
    const val PAIR_PORT = "Port"
    const val PAIR = "Pair"
    const val CONNECT_PORT_HINT =
        "Type the port shown on the TV's Wireless debugging screen (after the colon in the IP address)."
    const val CLASSIC_ACK =
        "This TV can't use an encrypted connection. SideSpeed can still connect the older way. " +
            "Anyone on your network could see the commands it sends."
    const val CLASSIC_ACK_CHECK = "I understand. Connect the older way."
    const val PAIR_OFFER = "This TV can use an encrypted connection. Pair it to keep checking it."
    const val NOT_CONTACTED = "SideSpeed isn't checking this TV until you choose."
    const val WIRELESS_OFF = "Turn wireless debugging back on to keep this TV up to date."
    const val TRY_AGAIN = "Try again"
    const val TYPE_THE_PORT = "Type the port"
    const val BAD_CONNECT_PORT = "Enter the port shown on the TV's Wireless debugging screen."

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
        TvStatus.NEEDS_ATTENTION -> "Not checking"
        TvStatus.UNKNOWN -> "Not checked yet"
    }
}

/** How a Restart-now press ended for one TV: reached while running, reached while not running, or never contacted. */
enum class RestartOutcome { RESTARTED, CHECKED, NOT_CHECKED }

/** The outcome of the latest Restart-now press for [tvId]; cleared when the screen has shown it. */
data class RestartResult(val tvId: String, val outcome: RestartOutcome)

/** Why a saved TV is waiting on the user instead of being checked. */
enum class TvAttention { NONE, LEGACY_ACK, LEGACY_PAIR, WIRELESS_OFF }

/** Pairing a TV that speaks wireless debugging. [tvId] is set when this replaces a saved TV's link. */
data class PairingForm(
    val address: String,
    val tvId: String? = null,
    val error: String? = null,
    val askConnectPort: Boolean = false,
)

/** The cleartext acknowledgment, shown before a classic TV is added. */
data class ClassicAck(val address: String, val checked: Boolean = false)

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
    /** What the Home card asks the user to do before this TV is checked again. */
    val attention: TvAttention = TvAttention.NONE,
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
    /** Addresses that answered with wireless debugging. Picking one opens pairing. */
    val tlsHosts: List<String> = emptyList(),
    /** Pairing in progress, or null when the form is closed. */
    val pairing: PairingForm? = null,
    /** A classic add waiting for the acknowledgment checkbox. */
    val classicAck: ClassicAck? = null,
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
    private val pairer: Pairer = Pairer { _, _, _ -> PairResult.Failed(PairCopy.FAILED) },
    private val ports: ConnectPortFinder = ConnectPortFinder { null },
    private val linkOffer: LinkOffer = LinkOffer { false },
) {
    private val overlayGate = overlay?.let(::OverlayGate)
    private val sessions = TvSessions(
        connector,
        store,
        classicTls = { address -> linkOffer.encryptedAvailable(address) },
        ports = ports,
    )
    /** Whether a saved classic TV offered TLS the last time Home asked. Avoids asking on every refresh. */
    private val tlsOffer = mutableMapOf<String, Boolean>()

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
    /**
     * Starts adding the TV at [address]. A classic TV is not contacted until [acknowledged] is true.
     * A TV that offers wireless debugging opens the pairing form instead.
     */
    fun addTvByAddress(address: String, acknowledged: Boolean = false) {
        val a = address.trim()
        var accepted = false
        _state.update {
            accepted = a !in it.adding && it.addedAddress != a
            if (accepted) it.copy(adding = it.adding + a, addErrors = it.addErrors - a) else it
        }
        if (!accepted) return
        locked {
            try {
                addTv(a, acknowledged)
                val failure = _state.value.error
                val waiting = failure == null &&
                    (_state.value.classicAck?.address == a || _state.value.pairing?.address == a)
                _state.update {
                    when {
                        failure != null -> it.copy(addErrors = it.addErrors + (a to failure))
                        waiting -> it
                        else -> it.copy(addedAddress = a)
                    }
                }
            } finally {
                _state.update { it.copy(adding = it.adding - a) }
            }
        }
    }

    /** The acknowledgment checkbox on the Add screen. */
    fun setClassicChecked(checked: Boolean) {
        _state.update { s -> s.classicAck?.let { s.copy(classicAck = it.copy(checked = checked)) } ?: s }
    }

    /** The Home card's Pair button: pair the saved TV at its current address. */
    fun pairSaved(tvId: String) {
        val tv = _state.value.tvs.firstOrNull { it.id == tvId } ?: return
        startPairing(tv.address, tvId)
    }

    /** Opens the pairing form for [address]. [tvId] is set when pairing replaces a saved TV's link. */
    fun startPairing(address: String, tvId: String? = null) {
        _state.update { it.copy(pairing = PairingForm(address.trim(), tvId), classicAck = null, error = null) }
    }

    fun cancelPairing() {
        _state.update { it.copy(pairing = null) }
    }

    /** Sends the 6-digit code and the pairing port, then looks up the connect port. */
    fun submitPair(code: String, portText: String) {
        val form = _state.value.pairing ?: return
        locked {
            val port = portText.trim().toIntOrNull()
            val result = if (port == null) PairResult.Failed(PairCopy.BAD_PORT) else pairer.pair(form.address, port, code.trim())
            if (result !is PairResult.Paired) {
                _state.update { it.copy(pairing = form.copy(error = result.message())) }
                return@locked
            }
            val connect = ports.find(form.address)?.takeIf { it != TvConnector.DEFAULT_PORT }
            if (connect == null) {
                _state.update { it.copy(pairing = form.copy(error = null, askConnectPort = true)) }
                return@locked
            }
            saveEncrypted(form, connect)
        }
    }

    /** The connect port typed from the TV's wireless debugging screen, after mDNS found none. */
    fun submitConnectPort(portText: String) {
        val form = _state.value.pairing ?: return
        val port = portText.trim().toIntOrNull()
        if (port == null || port !in 1..65535 || port == TvConnector.DEFAULT_PORT) {
            _state.update { it.copy(pairing = form.copy(error = Copy.BAD_CONNECT_PORT)) }
            return
        }
        locked { saveEncrypted(form, port) }
    }

    /** The one-time acknowledgment for a saved classic TV. Then it can be checked. */
    fun acknowledgeClassic(tvId: String) {
        locked {
            val tv = store.tvs().firstOrNull { it.id == tvId } ?: return@locked
            val link = tv.link
            if (link !is TvLink.Classic || link.ackAtEpochMs != null) return@locked
            store.updateTv(tvId) { it.copy(link = TvLink.Classic(clock())) }
            tlsOffer.remove(tv.address)
            runUpkeep(Trigger.APP_OPEN, tvId)
        }
    }

    /** Stores a connect port the user typed for an encrypted TV, then checks it. */
    fun useTypedPort(tvId: String, portText: String) {
        val port = portText.trim().toIntOrNull()
        if (port == null || port !in 1..65535 || port == TvConnector.DEFAULT_PORT) {
            _state.update { it.copy(error = Copy.BAD_CONNECT_PORT) }
            return
        }
        locked {
            store.updateTv(tvId) { current ->
                if (current.link is TvLink.Encrypted) current.copy(link = TvLink.Encrypted(port)) else current
            }
            runUpkeep(Trigger.APP_OPEN, tvId)
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
                tlsHosts = if (clearFound) emptyList() else it.tlsHosts,
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
                            found = it.found.isNotEmpty() || it.tlsHosts.isNotEmpty(),
                            complete = !cancelled,
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
        val trustedIdAtStart = trusted.id
        var lastTrustCheckMs = clock()
        try {
        discovery.run(DiscoveryPlan(scope, own = own, gateway = gateway, skip = resumeSkip)).collect { event ->
            when (event) {
                is DiscoveryEvent.Progress -> {
                    val now = clock()
                    if (now - lastTrustCheckMs >= 1_000) {
                        lastTrustCheckMs = now
                        val live = currentTrusted()
                        if (live == null || live.id != trustedIdAtStart) throw FindTrustLost()
                    }
                    _state.update { it.copy(scanProgress = "Scanned ${event.scanned} of ${event.total}") }
                }
                is DiscoveryEvent.Found -> {
                    val host = event.address.toString()
                    _state.update { s ->
                        if (host in saved || host in s.found || host in s.tlsHosts) s
                        else if (event.result == ProbeResult.AdbTls) s.copy(tlsHosts = s.tlsHosts + host)
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
        } catch (_: FindTrustLost) {
            val keptFound = _state.value.found
            val keptTls = _state.value.tlsHosts
            refresh(
                error = Copy.NOT_TRUSTED_ERROR,
                settleBusy = false,
                retainFound = keptFound,
                retainTlsHosts = keptTls,
            )
            return false
        }
        refresh(error = if (swept) null else Copy.CANT_SCAN, settleBusy = false)
        if (swept) _state.update { it.copy(noneFound = it.found.isEmpty() && it.tlsHosts.isEmpty()) }
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
            val trusted = currentTrusted()
            val overlays = store.trustedOverlays()
            val cards = _state.value.tvs.associateBy { it.id }
            val eligible = store.tvs().mapNotNull { saved ->
                if (!checkableHere(saved, trusted, overlays)) return@mapNotNull null
                val card = cards[saved.id] ?: return@mapNotNull null
                if (card.status != TvStatus.RUNNING || saved.id in parkedTvIds) return@mapNotNull null
                card
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
            val saved = store.tvs().firstOrNull { it.id == tv.id } ?: return
            val newSession = tv.id !in pollSessions
            val session = pollSessions.getOrPut(tv.id) { sessions.open(saved) }
            if (newSession) {
                val serial = session.run(TvCommands.SERIAL).trim()
                if (serial != saved.serial) {
                    runCatching { pollSessions.remove(tv.id)?.close() }
                    pollFailures.remove(tv.id)
                    parkedTvIds += tv.id
                    return
                }
            }
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

    private suspend fun addTv(address: String, acknowledged: Boolean) {
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
        if (address in _state.value.tlsHosts || linkOffer.encryptedAvailable(address) || ports.find(address) != null) {
            _state.update { it.copy(pairing = PairingForm(address), classicAck = null, error = null) }
            return
        }
        if (!acknowledged) {
            _state.update { it.copy(classicAck = ClassicAck(address), pairing = null, error = null) }
            return
        }
        _state.update { it.copy(busy = true, error = null, classicAck = null) }
        val identity = readIdentity(TvTarget("pending", address, TvLink.Classic(clock()))) ?: return
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
                link = TvLink.Classic(clock()),
            ),
        )
        _state.update { it.copy(classicAck = null, pairing = null) }
        runUpkeep(Trigger.APP_OPEN)
    }

    private suspend fun saveEncrypted(form: PairingForm, port: Int) {
        if (!isIpv4(form.address)) return refresh(error = Copy.BAD_ADDRESS)
        val ip = Ipv4.parse(form.address)
        if (ip == null || !AddressPolicy.contactable(ip)) return refresh(error = Copy.OUTSIDE_NETWORK)
        val existing = form.tvId?.let { id -> store.tvs().firstOrNull { it.id == id } }
        if (existing == null) {
            val binding = overlayGate?.bindingFor(ip, store.trustedOverlays())
            if (binding == null && AddressPolicy.isOverlay(ip) &&
                overlay?.source?.snapshot()?.peers?.any { it.shared && ip in it.ips } == true
            ) return refresh(error = Copy.SHARED_PEER)
            if (binding == null && currentTrusted() == null) return refresh(error = Copy.NOT_TRUSTED_ADD)
        }
        _state.update { it.copy(busy = true, error = null) }
        val identity = readIdentity(TvTarget(existing?.id ?: "pending", form.address, TvLink.Encrypted(port))) ?: run {
            val message = _state.value.error
            _state.update { it.copy(pairing = form.copy(error = message)) }
            return
        }
        val (serial, model, maker) = identity
        if (serial.isBlank()) {
            _state.update { it.copy(pairing = form.copy(error = Copy.NO_SERIAL), error = Copy.NO_SERIAL, busy = false) }
            return
        }
        if (existing != null) {
            if (existing.serial != serial) {
                _state.update { it.copy(pairing = form.copy(error = Copy.DIFFERENT_DEVICE), error = Copy.DIFFERENT_DEVICE, busy = false) }
                return
            }
            store.updateTv(existing.id) {
                it.copy(address = form.address, link = TvLink.Encrypted(port), status = TvStatus.UNKNOWN)
            }
            tlsOffer.remove(form.address)
            _state.update { it.copy(pairing = null, addedAddress = form.address) }
            runUpkeep(Trigger.APP_OPEN, existing.id)
            return
        }
        val binding = overlayGate?.bindingFor(ip, store.trustedOverlays())
        if (binding == null && AddressPolicy.isOverlay(ip) &&
            overlay?.source?.snapshot()?.peers?.any { it.shared && ip in it.ips } == true
        ) return refresh(error = Copy.SHARED_PEER)
        val trusted = if (binding == null) currentTrusted() ?: return refresh(error = Copy.NOT_TRUSTED_ADD) else null
        val scopeId = binding?.overlay?.id ?: trusted!!.id
        val prior = store.tvs().firstOrNull {
            it.serial == serial && (it.trustedNetworkId == scopeId || (binding == null && it.overlayPeerId == null))
        }
        store.saveTv(
            SavedTv(
                id = prior?.id ?: idGenerator(),
                name = model.ifBlank { "TV at ${form.address}" },
                address = form.address,
                serial = serial,
                trustedNetworkId = scopeId,
                overlayPeerId = binding?.peer?.id,
                isFireTv = maker.equals("Amazon", ignoreCase = true),
                link = TvLink.Encrypted(port),
            ),
        )
        _state.update { it.copy(pairing = null, classicAck = null, addedAddress = form.address) }
        runUpkeep(Trigger.APP_OPEN)
    }

    /** Opens [target] and reads the fixed identity fields. On failure, records the error and returns null. */
    private suspend fun readIdentity(target: TvTarget): Triple<String, String, String>? = try {
        sessions.open(target).use { session ->
            Triple(
                session.run(TvCommands.SERIAL),
                session.run(TvCommands.MODEL),
                session.run(TvCommands.MANUFACTURER),
            )
        }
    } catch (e: TvAuthPending) {
        refresh(error = Copy.ALLOW_STEP)
        null
    } catch (e: EncryptedAvailable) {
        _state.update { it.copy(pairing = PairingForm(target.address), classicAck = null, error = null, busy = false) }
        null
    } catch (e: ClassicNotAcknowledged) {
        _state.update { it.copy(classicAck = ClassicAck(target.address), pairing = null, error = null, busy = false) }
        null
    } catch (e: TvUnreachable) {
        refresh(error = Copy.UNREACHABLE)
        null
    } catch (e: EncryptedPortUnknown) {
        refresh(error = Copy.UNREACHABLE)
        null
    } catch (e: AdbConnectionLost) {
        refresh(error = Copy.UNREACHABLE)
        null
    }

    private suspend fun refresh(
        error: String? = null,
        settleBusy: Boolean = true,
        lastRestart: RestartResult? = null,
        updateLastRestart: Boolean = false,
        retainFound: List<String>? = null,
        retainTlsHosts: List<String>? = null,
    ) {
        val fp = fingerprints.current()
        val networks = store.trustedNetworks()
        val trusted = TrustMatcher.currentTrusted(fp, networks)
        val overlays = store.trustedOverlays()
        val tailnet = overlay?.source?.snapshot()?.let { snap ->
            TailnetState(snap.tailnet, overlays.any { o -> OverlayTrustMatcher.matches(snap, o) }, snap.running && snap.tun)
        }
        val tvs = store.tvs()
        activities.keys.retainAll(tvs.map { it.id }.toSet())
        val saved = tvs.map { it.address }.toSet()
        val cards = ArrayList<TvCardState>(tvs.size)
        tlsOffer.keys.retainAll(tvs.map { it.address }.toSet())
        for (tv in tvs) {
            val here = checkableHere(tv, trusted, overlays)
            val attention = attentionFor(tv)
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
                attention = attention,
            )
        }
        _state.update {
            it.copy(
                loaded = true,
                savedByNewerVersion = store.savedByNewerVersion,
                firstRun = networks.isEmpty() && overlays.isEmpty(),
                currentNetwork = fp,
                trustedNetworkName = trusted?.displayName,
                trustedNetworks = networks,
                trustedOverlays = overlays,
                tailnet = tailnet,
                tvs = cards,
                settings = store.settings(),
                busy = if (settleBusy) false else it.busy,
                lastRestart = if (updateLastRestart) lastRestart else it.lastRestart,
                error = error,
                found = retainFound ?: if (trusted == null) emptyList() else it.found.filter { h -> h !in saved },
                tlsHosts = retainTlsHosts ?: if (trusted == null) emptyList() else it.tlsHosts.filter { h -> h !in saved },
            )
        }
    }

    private suspend fun attentionFor(tv: SavedTv): TvAttention {
        val link = tv.link
        return when {
            link is TvLink.Encrypted && tv.status == TvStatus.NEEDS_ATTENTION -> TvAttention.WIRELESS_OFF
            link is TvLink.Classic && link.ackAtEpochMs == null ->
                if (offersTls(tv.address)) TvAttention.LEGACY_PAIR else TvAttention.LEGACY_ACK
            link is TvLink.Classic && tv.status == TvStatus.NEEDS_ATTENTION -> TvAttention.LEGACY_PAIR
            else -> TvAttention.NONE
        }
    }

    private suspend fun offersTls(address: String): Boolean {
        tlsOffer[address]?.let { return it }
        val offered = linkOffer.encryptedAvailable(address) || ports.find(address) != null
        tlsOffer[address] = offered
        return offered
    }

    private fun isIpv4(s: String): Boolean {
        val p = s.split('.')
        return p.size == 4 && p.all { o -> o.isNotEmpty() && o.length <= 3 && o.all { it in '0'..'9' } && o.toInt() <= 255 }
    }

    private class FindTrustLost : Exception()

    private companion object {
        var counter = 0
        /** Home-screen activity polls run this often, one TV at a time, reusing each TV's session. */
        const val ACTIVITY_POLL_MS = 3_000L
        /** Consecutive poll failures that park a TV until the next screen visit. */
        const val MAX_ACTIVITY_FAILURES = 3
    }
}
