package io.github.devjardins.sidespeed.companion.upkeep

import io.github.devjardins.sidespeed.adb.AdbConnectionLost
import io.github.devjardins.sidespeed.companion.discovery.sweepScope
import io.github.devjardins.sidespeed.companion.net.AddressPolicy
import io.github.devjardins.sidespeed.companion.net.Cidr
import io.github.devjardins.sidespeed.companion.net.FingerprintSource
import io.github.devjardins.sidespeed.companion.net.Ipv4
import io.github.devjardins.sidespeed.companion.net.OverlayTrustMatcher
import io.github.devjardins.sidespeed.companion.net.TrustMatcher
import io.github.devjardins.sidespeed.companion.net.TrustedNetwork
import io.github.devjardins.sidespeed.companion.net.TrustedOverlay
import io.github.devjardins.sidespeed.companion.store.CompanionStore
import io.github.devjardins.sidespeed.companion.store.PromptedHost
import io.github.devjardins.sidespeed.companion.store.Reach
import io.github.devjardins.sidespeed.companion.store.SavedTv
import io.github.devjardins.sidespeed.companion.store.TvStatus
import io.github.devjardins.sidespeed.companion.tv.HelperKeeper
import io.github.devjardins.sidespeed.companion.tv.TvAuthPending
import io.github.devjardins.sidespeed.companion.tv.TvCommands
import io.github.devjardins.sidespeed.companion.tv.TvConnector
import io.github.devjardins.sidespeed.companion.tv.TvUnreachable
import io.github.devjardins.sidespeed.companion.tv.VisitResult
import io.github.devjardins.sidespeed.companion.tv.open
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Why a check runs. Only the user-initiated ones may retry a TV whose "Allow" prompt is waiting. */
enum class Trigger { BACKGROUND, APP_OPEN, RESTART_NOW, NETWORK_JOINED }

/**
 * [gated] is true when the run did nothing because the current network is not a Trusted Network, or stopped part
 * way because the network changed. [visited] has the new status of every TV a connection was attempted to.
 */
data class UpkeepReport(val gated: Boolean, val visited: Map<String, TvStatus>, val restartedNames: List<String>)

/**
 * One pass over the saved TVs. The companion touches the network only from here (and the user's own "Find TVs"),
 * so the rules are strict:
 *
 * - **Gate.** Nothing happens unless the current fingerprint matches a Trusted Network (none chosen yet = gated).
 *   The gate is read again before every TV visit, before a re-find scan, before *each* re-find connection and
 *   before the visit at a re-found address. If the network is no longer that Trusted Network the run stops there
 *   and reports `gated`. A closed overlay gate skips the remaining overlay TVs but not the
 *   LAN ones, and a closed LAN gate skips the remaining LAN TVs but not the overlay ones;
 *   the report is then `gated`.
 * - Only TVs saved on that Trusted Network are checked. Each TV is re-read from the store just before its visit,
 *   and results are written with [CompanionStore.updateTv], touching only the upkeep fields, so a TV removed or
 *   edited during a run is not brought back or reverted.
 * - **Allow prompt.** A TV whose prompt is waiting ([SavedTv.skipBackgroundUntilUserAction]) is skipped by
 *   [Trigger.BACKGROUND] and [Trigger.NETWORK_JOINED]; [Trigger.APP_OPEN] and [Trigger.RESTART_NOW] clear the
 *   flag and try. Foreign devices that showed the prompt during a re-find are remembered
 *   ([CompanionStore.promptedHosts], 24 h) and skipped by later re-finds; user actions clear that list.
 *
 * Outcomes:
 * - [TvUnreachable], a lost connection ([AdbConnectionLost]) and [VisitResult.WrongDevice] are failed checks:
 *   `offlineCount` grows. Unreachable or lost keep the last known status until the third consecutive failure,
 *   which is [TvStatus.OFFLINE]; WrongDevice shows [TvStatus.WRONG_DEVICE]. No faster retry.
 * - Unreachable and WrongDevice can lead to a re-find (a lost connection means our TV answered at that address):
 *   once `offlineCount >= 3` and an hour has passed since the last re-find (or the clock moved back), scan the
 *   Trusted Network's subnet for port 5555 (starting around the TV's last address), keep only usable host
 *   addresses inside it, open each host not recently prompted, read only [TvCommands.SERIAL], close, and take
 *   the first host whose serial equals the saved one. The
 *   address is updated and the TV visited there. Hosts with another serial get nothing else.
 *   `lastRefindEpochMs` is set whenever the scan runs.
 * - [TvAuthPending] is [TvStatus.WAITING_FOR_ALLOW] and sets the skip flag; the TV answered, so `offlineCount`
 *   resets.
 * - A completed visit (other than WrongDevice) resets `offlineCount` and sets the status from the
 *   [VisitResult]; Started and Restarted add the TV's name to `restartedNames`.
 *
 * Runs are serialised: a second [runOnce] waits for the first.
 */
class Upkeep(
    private val store: CompanionStore,
    private val fingerprints: FingerprintSource,
    private val connector: TvConnector,
    private val keeper: HelperKeeper,
    private val scanner: Scanner,
    private val overlay: OverlayAccess? = null,
    private val clock: () -> Long,
) {
    private val mutex = Mutex()
    private val overlayGate = overlay?.let(::OverlayGate)

    private sealed interface Attempt {
        data class Visited(val result: VisitResult) : Attempt
        data object Unreachable : Attempt
        data object AuthPending : Attempt
        data object Lost : Attempt
    }

    private sealed interface Refind {
        data class Found(val host: String) : Refind
        data object NotFound : Refind
        data object GateClosed : Refind
    }

    /**
     * [gateClosed]: the network stopped being the Trusted Network part way through this TV.
     * [attempted]: a connection was tried, so the TV belongs in [UpkeepReport.visited].
     */
    private data class Checked(val tv: SavedTv, val gateClosed: Boolean = false, val attempted: Boolean = true)

    suspend fun runOnce(trigger: Trigger, onlyTvId: String? = null): UpkeepReport = mutex.withLock {
        // A store saved by a newer copy is read-only: upkeep still checks and restarts, but saves nothing.
        val readOnly = store.savedByNewerVersion
        val fp = fingerprints.current()
        val matched = TrustMatcher.currentTrusted(fp, store.trustedNetworks())
        // A match on a record made before the gateway MAC or Wi-Fi name was read hardens that record.
        val lan = if (fp != null && matched != null && !readOnly) {
            TrustMatcher.upgraded(matched, fp)?.let { store.saveTrustedNetwork(it); it } ?: matched
        } else matched
        val tailnet = currentOverlay()
        if (lan == null && tailnet == null) {
            return@withLock UpkeepReport(gated = true, visited = emptyMap(), restartedNames = emptyList())
        }

        val visited = LinkedHashMap<String, TvStatus>()
        val restarted = mutableListOf<String>()
        var overlayClosed = false
        var lanClosed = false
        val userAction = trigger == Trigger.APP_OPEN || trigger == Trigger.RESTART_NOW
        if (userAction && !readOnly && store.promptedHosts().isNotEmpty()) store.savePromptedHosts(emptyList())

        val scopeIds = setOfNotNull(lan?.id, tailnet?.id)
        val ids = store.tvs().filter { it.trustedNetworkId in scopeIds && (onlyTvId == null || it.id == onlyTvId) }.map { it.id }
        for (id in ids) {
            // Fresh copy: the user may have removed or edited it since the run started.
            val saved = store.tvs().firstOrNull { it.id == id } ?: continue
            if (saved.trustedNetworkId !in scopeIds || saved.serial.isBlank()) continue
            if (saved.skipBackgroundUntilUserAction && !userAction) continue

            val checked = if (tailnet != null && saved.trustedNetworkId == tailnet.id) {
                // A closed overlay gate ends overlay visits for this run; TVs on the LAN still get theirs.
                if (overlayClosed) continue
                checkOverlay(saved, tailnet, restarted) ?: run { overlayClosed = true; null }
            } else {
                // A closed LAN gate skips the remaining LAN TVs but not the overlay ones.
                if (lanClosed) continue
                if (lan == null || !gateOpen(lan)) run { lanClosed = true; null }
                else check(saved, lan, restarted)
            } ?: continue
            // Read-only: the check still counts as visited, but nothing is written back.
            val stored = if (readOnly) true else store.updateTv(id) { current -> merge(current, saved, checked.tv) }
            if (stored && checked.attempted) visited[id] = checked.tv.status
            if (checked.gateClosed) {
                // Only a LAN check reports a closed gate; the overlay branch keeps its own flag.
                if (tailnet != null && saved.trustedNetworkId == tailnet.id) return@withLock UpkeepReport(true, visited, restarted)
                lanClosed = true
                continue
            }
        }
        UpkeepReport(gated = lanClosed || overlayClosed, visited = visited, restartedNames = restarted)
    }

    /** The trusted overlay Tailscale matches right now; Tailscale is not even asked when none is trusted. */
    private suspend fun currentOverlay(): TrustedOverlay? {
        val access = overlay ?: return null
        val trusted = store.trustedOverlays()
        if (trusted.isEmpty()) return null
        return OverlayTrustMatcher.currentTrusted(access.source.snapshot(), trusted)
    }

    /**
     * One visit to a TV on a trusted overlay, or null when the overlay gate is closed. A peer that is gone or
     * unusable counts as a failed check without a connection. There is no re-find: the
     * peer's node ID already says where the TV is, and a scan would be a sweep of a tailnet. A peer that moved is
     * followed only when the serial matches at its new address.
     */
    private suspend fun checkOverlay(tv: SavedTv, trusted: TrustedOverlay, restarted: MutableList<String>): Checked? {
        val gate = overlayGate ?: return null
        val address = when (val v = gate.verdict(trusted, tv)) {
            OverlayVerdict.Closed -> return null
            OverlayVerdict.PeerUnavailable -> return Checked(
                apply(tv.copy(skipBackgroundUntilUserAction = false), Attempt.Unreachable, clock(), restarted, dialled = null),
                attempted = false,
            )
            is OverlayVerdict.Open -> v.address
        }
        val attempt = attempt(address, tv.serial)
        val result = apply(tv.copy(skipBackgroundUntilUserAction = false), attempt, clock(), restarted, dialled = address)
        val verified = attempt is Attempt.Visited && attempt.result != VisitResult.WrongDevice
        return Checked(if (verified) result.copy(address = address) else result)
    }

    /**
     * Applies only the upkeep fields of [result] to the store's [current] copy. The address moves only if the
     * re-find moved it and the user hasn't changed it meanwhile.
     */
    private fun merge(current: SavedTv, before: SavedTv, result: SavedTv): SavedTv = current.copy(
        status = result.status,
        lastCheckedEpochMs = result.lastCheckedEpochMs,
        offlineCount = result.offlineCount,
        lastRefindEpochMs = result.lastRefindEpochMs,
        skipBackgroundUntilUserAction = result.skipBackgroundUntilUserAction,
        reach = result.reach,
        address = if (result.address != before.address && current.address == before.address) result.address else current.address,
    )

    /** Checks one TV and, when due, re-finds it. */
    private suspend fun check(tv: SavedTv, trusted: TrustedNetwork, restarted: MutableList<String>): Checked {
        val now = clock()
        val first = attempt(tv.address, tv.serial)
        val afterFirst = apply(tv.copy(skipBackgroundUntilUserAction = false), first, now, restarted, dialled = tv.address)
        val addressFailed = first == Attempt.Unreachable ||
            (first is Attempt.Visited && first.result == VisitResult.WrongDevice)
        if (!addressFailed || !refindDue(afterFirst, now)) return Checked(afterFirst)

        if (!gateOpen(trusted)) return Checked(afterFirst, gateClosed = true)
        val scanned = afterFirst.copy(lastRefindEpochMs = now)
        return when (val r = refind(scanned, trusted, now)) {
            Refind.NotFound -> Checked(scanned)
            Refind.GateClosed -> Checked(scanned, gateClosed = true)
            is Refind.Found -> {
                // The serial matched, so the new address is safe to keep even if the visit can't happen now.
                val moved = scanned.copy(address = r.host)
                if (!gateOpen(trusted)) return Checked(moved, gateClosed = true)
                Checked(apply(moved, attempt(r.host, moved.serial), now, restarted, dialled = r.host))
            }
        }
    }

    /** Due after 3 failed checks and an hour since the last re-find; a negative gap (clock moved back) is due. */
    private fun refindDue(tv: SavedTv, now: Long): Boolean {
        if (tv.offlineCount < OFFLINE_AFTER) return false
        val last = tv.lastRefindEpochMs ?: return true
        val gap = now - last
        return gap < 0 || gap >= REFIND_INTERVAL_MS
    }

    /**
     * Streams the Trusted Network's subnet for the TV's serial, connecting as hosts answer instead of
     * waiting for the whole sweep. The first serial match ends the run; leaving the Trusted Network
     * mid-sweep stops it. The gate is checked before every connection and rechecked while waiting.
     */
    private suspend fun refind(tv: SavedTv, trusted: TrustedNetwork, now: Long): Refind {
        val subnet = Cidr.parse(trusted.fingerprint.subnet) ?: return Refind.NotFound
        // Like Find TVs, a re-find only ever sweeps private (RFC 1918) space, whatever the saved fingerprint says.
        val scope = sweepScope(subnet, Ipv4.parse(tv.address), Ipv4.parse(trusted.fingerprint.gatewayIp))
        if (!AddressPolicy.sweepable(scope)) return Refind.NotFound
        val seen = mutableSetOf<String>()
        var found: String? = null
        var gateClosed = false
        try {
            coroutineScope {
                // The sweep can run a minute; leaving the network stops it instead of connecting from it.
                // Cancel the sweep scope itself: cancelling only the watcher would leave the sweep hanging.
                val sweep = this
                val gateWatcher = launch {
                    while (true) {
                        delay(REFIND_GATE_RECHECK_MS)
                        if (!gateOpen(trusted)) {
                            gateClosed = true
                            sweep.cancel(CancellationException("no longer on the trusted network"))
                        }
                    }
                }
                try {
                    scanner.hosts(scope.toString(), TvConnector.DEFAULT_PORT, near = tv.address).collect { host ->
                        val ip = Ipv4.parse(host)
                        if (ip == null || ip !in scope || ip == scope.network || ip == scope.broadcast) return@collect
                        if (!seen.add(host)) return@collect
                        val skip = store.promptedHosts().any { it.trustedNetworkId == trusted.id && it.address == host && it.isFresh(now) }
                        if (skip) return@collect
                        if (!gateOpen(trusted)) {
                            gateClosed = true
                            sweep.cancel(CancellationException("no longer on the trusted network"))
                            return@collect
                        }
                        val serial = try {
                            connector.open(host).use { it.run(TvCommands.SERIAL) }
                        } catch (e: TvUnreachable) {
                            return@collect
                        } catch (e: TvAuthPending) {
                            rememberPrompted(trusted.id, host, now)
                            return@collect
                        } catch (e: AdbConnectionLost) {
                            return@collect
                        }
                        if (serial.isNotEmpty() && serial == tv.serial) {
                            found = host
                            sweep.cancel(CancellationException("serial matched; sweep no longer needed"))
                        }
                    }
                } finally {
                    gateWatcher.cancel()
                }
            }
        } catch (e: CancellationException) {
            if (found == null && !gateClosed) throw e
        }
        if (gateClosed) return Refind.GateClosed
        val match = found
        if (match != null) return Refind.Found(match)
        return Refind.NotFound
    }

    private suspend fun rememberPrompted(networkId: String, host: String, now: Long) {
        if (store.savedByNewerVersion) return
        val kept = store.promptedHosts().filter { it.isFresh(now) && !(it.trustedNetworkId == networkId && it.address == host) }
        store.savePromptedHosts(kept + PromptedHost(networkId, host, now))
    }

    private suspend fun attempt(address: String, serial: String): Attempt = try {
        connector.open(address).use { Attempt.Visited(keeper.visit(it, serial)) }
    } catch (e: TvUnreachable) {
        Attempt.Unreachable
    } catch (e: TvAuthPending) {
        Attempt.AuthPending
    } catch (e: AdbConnectionLost) {
        Attempt.Lost
    }

    /**
     * How the last check reached the TV: the address actually dialled decides LAN vs tailnet, and an answered
     * check counts as reached even when SideSpeed isn't installed or the helper isn't running. A lost
     * connection never got an answer, so it is unreachable.
     */
    private fun reachOf(dialled: String?, a: Attempt): Reach = when {
        a is Attempt.Visited || a == Attempt.AuthPending ->
            if (dialled != null && Ipv4.parse(dialled)?.let(AddressPolicy::isOverlay) == true) Reach.REACHED_TAILNET
            else Reach.REACHED_LAN
        else -> Reach.UNREACHABLE
    }

    private fun apply(tv: SavedTv, a: Attempt, now: Long, restarted: MutableList<String>, dialled: String?): SavedTv {
        val reach = reachOf(dialled, a)
        return when (a) {
            Attempt.Unreachable, Attempt.Lost -> {
                val count = tv.offlineCount + 1
                tv.copy(
                    offlineCount = count,
                    status = if (count >= OFFLINE_AFTER) TvStatus.OFFLINE else tv.status,
                    lastCheckedEpochMs = now,
                    reach = reach,
                )
            }
            Attempt.AuthPending -> tv.copy(
                status = TvStatus.WAITING_FOR_ALLOW,
                skipBackgroundUntilUserAction = true,
                offlineCount = 0,
                lastCheckedEpochMs = now,
                reach = reach,
            )
            is Attempt.Visited -> if (a.result == VisitResult.WrongDevice) {
                // Something else answers at this address: count it toward a re-find, but say what we saw.
                tv.copy(status = TvStatus.WRONG_DEVICE, offlineCount = tv.offlineCount + 1, lastCheckedEpochMs = now, reach = reach)
            } else {
                val status = when (a.result) {
                    VisitResult.Running -> TvStatus.RUNNING
                    VisitResult.Started, VisitResult.Restarted -> TvStatus.RUNNING.also { restarted += tv.name }
                    VisitResult.NotInstalled -> TvStatus.NOT_INSTALLED
                    is VisitResult.StartFailed -> TvStatus.START_FAILED
                    VisitResult.WrongDevice -> TvStatus.WRONG_DEVICE
                }
                tv.copy(status = status, offlineCount = 0, lastCheckedEpochMs = now, reach = reach)
            }
        }
    }

    private suspend fun gateOpen(trusted: TrustedNetwork): Boolean =
        TrustMatcher.currentTrusted(fingerprints.current(), store.trustedNetworks())?.id == trusted.id

    private companion object {
        const val OFFLINE_AFTER = 3
        const val REFIND_INTERVAL_MS = 60 * 60 * 1000L
        /** How often a running re-find sweep rechecks that the network is still the Trusted Network. */
        const val REFIND_GATE_RECHECK_MS = 10_000L
    }
}
