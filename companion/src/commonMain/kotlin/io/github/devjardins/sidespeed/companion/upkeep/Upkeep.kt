package io.github.devjardins.sidespeed.companion.upkeep

import io.github.devjardins.sidespeed.adb.AdbConnectionLost
import io.github.devjardins.sidespeed.companion.net.FingerprintSource
import io.github.devjardins.sidespeed.companion.net.Subnets
import io.github.devjardins.sidespeed.companion.net.TrustMatcher
import io.github.devjardins.sidespeed.companion.net.TrustedNetwork
import io.github.devjardins.sidespeed.companion.store.CompanionStore
import io.github.devjardins.sidespeed.companion.store.PromptedHost
import io.github.devjardins.sidespeed.companion.store.SavedTv
import io.github.devjardins.sidespeed.companion.store.TvStatus
import io.github.devjardins.sidespeed.companion.tv.HelperKeeper
import io.github.devjardins.sidespeed.companion.tv.TvAuthPending
import io.github.devjardins.sidespeed.companion.tv.TvCommands
import io.github.devjardins.sidespeed.companion.tv.TvConnector
import io.github.devjardins.sidespeed.companion.tv.TvUnreachable
import io.github.devjardins.sidespeed.companion.tv.VisitResult
import io.github.devjardins.sidespeed.companion.tv.open
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
 *   and reports `gated`.
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
 *   Trusted Network's /24 for port 5555, keep only addresses inside it, open each host not recently prompted,
 *   read only [TvCommands.SERIAL], close, and take the first host whose serial equals the saved one. The
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
    private val clock: () -> Long,
) {
    private val mutex = Mutex()

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

    /** [gateClosed]: the network stopped being the Trusted Network part way through this TV. */
    private data class Checked(val tv: SavedTv, val gateClosed: Boolean = false)

    suspend fun runOnce(trigger: Trigger): UpkeepReport = mutex.withLock {
        val trusted = TrustMatcher.currentTrusted(fingerprints.current(), store.trustedNetworks())
            ?: return@withLock UpkeepReport(gated = true, visited = emptyMap(), restartedNames = emptyList())

        val visited = LinkedHashMap<String, TvStatus>()
        val restarted = mutableListOf<String>()
        val userAction = trigger == Trigger.APP_OPEN || trigger == Trigger.RESTART_NOW
        if (userAction && store.promptedHosts().isNotEmpty()) store.savePromptedHosts(emptyList())

        val ids = store.tvs().filter { it.trustedNetworkId == trusted.id }.map { it.id }
        for (id in ids) {
            // Fresh copy: the user may have removed or edited it since the run started.
            val saved = store.tvs().firstOrNull { it.id == id } ?: continue
            if (saved.trustedNetworkId != trusted.id || saved.serial.isBlank()) continue
            if (saved.skipBackgroundUntilUserAction && !userAction) continue
            if (!gateOpen(trusted)) return@withLock UpkeepReport(true, visited, restarted)

            val checked = check(saved, trusted, restarted)
            val stored = store.updateTv(id) { current -> merge(current, saved, checked.tv) }
            if (stored) visited[id] = checked.tv.status
            if (checked.gateClosed) return@withLock UpkeepReport(true, visited, restarted)
        }
        UpkeepReport(gated = false, visited = visited, restartedNames = restarted)
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
        address = if (result.address != before.address && current.address == before.address) result.address else current.address,
    )

    /** Checks one TV and, when due, re-finds it. */
    private suspend fun check(tv: SavedTv, trusted: TrustedNetwork, restarted: MutableList<String>): Checked {
        val now = clock()
        val first = attempt(tv.address, tv.serial)
        val afterFirst = apply(tv.copy(skipBackgroundUntilUserAction = false), first, now, restarted)
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
                Checked(apply(moved, attempt(r.host, moved.serial), now, restarted))
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

    /** Scans the Trusted Network's /24 for the TV's serial, checking the gate before every connection. */
    private suspend fun refind(tv: SavedTv, trusted: TrustedNetwork, now: Long): Refind {
        val subnet = Subnets.normalize(trusted.fingerprint.subnet)?.takeIf { it.endsWith("/24") } ?: return Refind.NotFound
        val hosts = scanner.scan(subnet, TvConnector.DEFAULT_PORT).filter { inSubnet(it, subnet) }.distinct()
        for (host in hosts) {
            val skip = store.promptedHosts().any { it.trustedNetworkId == trusted.id && it.address == host && it.isFresh(now) }
            if (skip) continue
            if (!gateOpen(trusted)) return Refind.GateClosed
            val serial = try {
                connector.open(host).use { it.run(TvCommands.SERIAL) }
            } catch (e: TvUnreachable) {
                continue
            } catch (e: TvAuthPending) {
                rememberPrompted(trusted.id, host, now)
                continue
            } catch (e: AdbConnectionLost) {
                continue
            }
            if (serial.isNotEmpty() && serial == tv.serial) return Refind.Found(host)
        }
        return Refind.NotFound
    }

    private suspend fun rememberPrompted(networkId: String, host: String, now: Long) {
        val kept = store.promptedHosts().filter { it.isFresh(now) && !(it.trustedNetworkId == networkId && it.address == host) }
        store.savePromptedHosts(kept + PromptedHost(networkId, host, now))
    }

    /** A plain dotted IPv4 host address (.1 to .254) inside the normalised /24 [subnet]. */
    private fun inSubnet(host: String, subnet: String): Boolean {
        val octets = host.split('.')
        if (octets.size != 4 || octets.any { o -> o.isEmpty() || o.length > 3 || !o.all { it in '0'..'9' } }) return false
        if (octets.last().toInt() !in 1..254) return false
        return Subnets.normalize("$host/24") == subnet
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

    private fun apply(tv: SavedTv, a: Attempt, now: Long, restarted: MutableList<String>): SavedTv = when (a) {
        Attempt.Unreachable, Attempt.Lost -> {
            val count = tv.offlineCount + 1
            tv.copy(
                offlineCount = count,
                status = if (count >= OFFLINE_AFTER) TvStatus.OFFLINE else tv.status,
                lastCheckedEpochMs = now,
            )
        }
        Attempt.AuthPending -> tv.copy(
            status = TvStatus.WAITING_FOR_ALLOW,
            skipBackgroundUntilUserAction = true,
            offlineCount = 0,
            lastCheckedEpochMs = now,
        )
        is Attempt.Visited -> if (a.result == VisitResult.WrongDevice) {
            // Something else answers at this address: count it toward a re-find, but say what we saw.
            tv.copy(status = TvStatus.WRONG_DEVICE, offlineCount = tv.offlineCount + 1, lastCheckedEpochMs = now)
        } else {
            val status = when (a.result) {
                VisitResult.Running -> TvStatus.RUNNING
                VisitResult.Started, VisitResult.Restarted -> TvStatus.RUNNING.also { restarted += tv.name }
                VisitResult.NotInstalled -> TvStatus.NOT_INSTALLED
                is VisitResult.StartFailed -> TvStatus.START_FAILED
                VisitResult.WrongDevice -> TvStatus.WRONG_DEVICE
            }
            tv.copy(status = status, offlineCount = 0, lastCheckedEpochMs = now)
        }
    }

    private suspend fun gateOpen(trusted: TrustedNetwork): Boolean =
        TrustMatcher.currentTrusted(fingerprints.current(), store.trustedNetworks())?.id == trusted.id

    private companion object {
        const val OFFLINE_AFTER = 3
        const val REFIND_INTERVAL_MS = 60 * 60 * 1000L
    }
}
