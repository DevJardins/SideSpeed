package io.github.devjardins.sidespeed.companion.upkeep

import io.github.devjardins.sidespeed.adb.AdbConnectionLost
import io.github.devjardins.sidespeed.companion.net.FingerprintSource
import io.github.devjardins.sidespeed.companion.net.Subnets
import io.github.devjardins.sidespeed.companion.net.TrustMatcher
import io.github.devjardins.sidespeed.companion.net.TrustedNetwork
import io.github.devjardins.sidespeed.companion.store.CompanionStore
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
 * - Nothing happens unless the current fingerprint matches a Trusted Network (none chosen yet = gated). The
 *   gate is read again before every TV after the first and before every re-find scan or re-find visit; if the
 *   network is no longer that Trusted Network the run stops there and reports `gated`.
 * - Only TVs saved on that Trusted Network are checked.
 * - A TV whose "Allow USB debugging?" prompt is waiting ([SavedTv.skipBackgroundUntilUserAction]) is skipped by
 *   [Trigger.BACKGROUND] and [Trigger.NETWORK_JOINED], so a background check never pops the prompt again.
 *   [Trigger.APP_OPEN] and [Trigger.RESTART_NOW] clear the flag and try.
 *
 * Outcomes:
 * - [TvUnreachable] or a connection lost mid-visit ([AdbConnectionLost]) is a failed check: `offlineCount` grows
 *   and the last known status is kept until the third consecutive failure, which is [TvStatus.OFFLINE]. There is
 *   no faster retry.
 * - Only an unreachable address leads to a re-find (a lost connection means the address did answer ADB): once
 *   `offlineCount >= 3` and at least an hour since the last re-find, scan the Trusted Network's /24 for port
 *   5555, open each answering host, read only [TvCommands.SERIAL], close, and take the first host whose serial
 *   equals the saved one. The address is updated and the TV visited there. Hosts with another serial get nothing
 *   else. `lastRefindEpochMs` is set whenever the scan runs, found or not.
 * - [TvAuthPending] is [TvStatus.WAITING_FOR_ALLOW] and sets the skip flag; the TV answered, so `offlineCount`
 *   resets.
 * - A completed visit resets `offlineCount` and sets the status from the [VisitResult]; Started and Restarted
 *   add the TV's name to `restartedNames`.
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

    /** [gateClosed]: the network stopped being the Trusted Network part way through this TV. */
    private data class Checked(val tv: SavedTv, val gateClosed: Boolean = false)

    suspend fun runOnce(trigger: Trigger): UpkeepReport = mutex.withLock {
        val trusted = TrustMatcher.currentTrusted(fingerprints.current(), store.trustedNetworks())
            ?: return@withLock UpkeepReport(gated = true, visited = emptyMap(), restartedNames = emptyList())

        val visited = LinkedHashMap<String, TvStatus>()
        val restarted = mutableListOf<String>()
        val userAction = trigger == Trigger.APP_OPEN || trigger == Trigger.RESTART_NOW
        val candidates = store.tvs().filter { it.trustedNetworkId == trusted.id && it.serial.isNotBlank() }

        var first = true
        for (saved in candidates) {
            if (saved.skipBackgroundUntilUserAction && !userAction) continue
            // The opening gate check covers the first TV; every later one is checked again.
            if (!first && !gateOpen(trusted)) return@withLock UpkeepReport(true, visited, restarted)
            first = false
            val checked = check(saved.copy(skipBackgroundUntilUserAction = false), trusted, restarted)
            store.saveTv(checked.tv)
            visited[checked.tv.id] = checked.tv.status
            if (checked.gateClosed) return@withLock UpkeepReport(true, visited, restarted)
        }
        UpkeepReport(gated = false, visited = visited, restartedNames = restarted)
    }

    /** Checks one TV and, when due, re-finds it. */
    private suspend fun check(tv: SavedTv, trusted: TrustedNetwork, restarted: MutableList<String>): Checked {
        val now = clock()
        val first = attempt(tv.address, tv.serial)
        val afterFirst = apply(tv, first, now, restarted)
        if (first != Attempt.Unreachable || !refindDue(afterFirst, now)) return Checked(afterFirst)

        if (!gateOpen(trusted)) return Checked(afterFirst, gateClosed = true)
        val scanned = afterFirst.copy(lastRefindEpochMs = now)
        val found = refind(scanned, trusted) ?: return Checked(scanned)
        if (!gateOpen(trusted)) return Checked(scanned, gateClosed = true)
        val moved = scanned.copy(address = found)
        return Checked(apply(moved, attempt(found, moved.serial), now, restarted))
    }

    private fun refindDue(tv: SavedTv, now: Long): Boolean =
        tv.offlineCount >= OFFLINE_AFTER && (tv.lastRefindEpochMs == null || now - tv.lastRefindEpochMs >= REFIND_INTERVAL_MS)

    /** Scans the Trusted Network's /24; returns the first answering address whose serial matches, or null. */
    private suspend fun refind(tv: SavedTv, trusted: TrustedNetwork): String? {
        val subnet = Subnets.normalize(trusted.fingerprint.subnet)?.takeIf { it.endsWith("/24") } ?: return null
        for (host in scanner.scan(subnet, TvConnector.DEFAULT_PORT)) {
            val serial = try {
                connector.open(host).use { it.run(TvCommands.SERIAL) }
            } catch (e: TvUnreachable) {
                continue
            } catch (e: TvAuthPending) {
                continue
            } catch (e: AdbConnectionLost) {
                continue
            }
            if (serial.isNotEmpty() && serial == tv.serial) return host
        }
        return null
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
        is Attempt.Visited -> {
            val status = when (a.result) {
                VisitResult.Running -> TvStatus.RUNNING
                VisitResult.Started, VisitResult.Restarted -> TvStatus.RUNNING.also { restarted += tv.name }
                VisitResult.NotInstalled -> TvStatus.NOT_INSTALLED
                VisitResult.WrongDevice -> TvStatus.WRONG_DEVICE
                is VisitResult.StartFailed -> TvStatus.START_FAILED
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
