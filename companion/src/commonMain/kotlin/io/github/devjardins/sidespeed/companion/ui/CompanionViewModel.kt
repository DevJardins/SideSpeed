package io.github.devjardins.sidespeed.companion.ui

import io.github.devjardins.sidespeed.adb.AdbConnectionLost
import io.github.devjardins.sidespeed.companion.net.FingerprintSource
import io.github.devjardins.sidespeed.companion.net.NetworkFingerprint
import io.github.devjardins.sidespeed.companion.net.Subnets
import io.github.devjardins.sidespeed.companion.net.TrustMatcher
import io.github.devjardins.sidespeed.companion.net.TrustedNetwork
import io.github.devjardins.sidespeed.companion.store.CompanionSettings
import io.github.devjardins.sidespeed.companion.store.CompanionStore
import io.github.devjardins.sidespeed.companion.store.SavedTv
import io.github.devjardins.sidespeed.companion.store.TvStatus
import io.github.devjardins.sidespeed.companion.tv.TvAuthPending
import io.github.devjardins.sidespeed.companion.tv.TvCommands
import io.github.devjardins.sidespeed.companion.tv.TvConnector
import io.github.devjardins.sidespeed.companion.tv.TvUnreachable
import io.github.devjardins.sidespeed.companion.tv.open
import io.github.devjardins.sidespeed.companion.upkeep.Scanner
import io.github.devjardins.sidespeed.companion.upkeep.Trigger
import io.github.devjardins.sidespeed.companion.upkeep.Upkeep
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

object Copy {
    const val NOT_TRUSTED_ERROR = "Join a Trusted Network to find TVs"
    const val NOT_FIRE_TV_NOTE =
        "Not a Fire TV: SideSpeed on this TV handles setup itself, and the companion can't reach it after restarts."
    const val ALLOW_STEP = "Tick Always allow on your TV"
    const val BAD_ADDRESS = "Enter the TV's address, like 192.168.1.50"
    const val NO_SERIAL = "That device didn't identify itself, so it can't be added"
    const val OUTSIDE_NETWORK = "That address isn't on your Trusted Network"
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
    val statusLabel: String,
    val checkedLabel: String?,
    val note: String?,
)

data class CompanionUiState(
    val loaded: Boolean = false,
    /** No Trusted Network chosen yet: the first-run screen shows and nothing touches the network. */
    val firstRun: Boolean = true,
    val currentNetwork: NetworkFingerprint? = null,
    val trustedNetworkName: String? = null,
    val trustedNetworks: List<TrustedNetwork> = emptyList(),
    val tvs: List<TvCardState> = emptyList(),
    val settings: CompanionSettings = CompanionSettings(),
    val busy: Boolean = false,
    val error: String? = null,
    /** Addresses of TVs found by "Find TVs" that aren't saved yet. */
    val found: List<String> = emptyList(),
)

class CompanionViewModel(
    private val store: CompanionStore,
    private val fingerprints: FingerprintSource,
    private val upkeep: Upkeep,
    private val connector: TvConnector,
    private val scanner: Scanner,
    private val scope: CoroutineScope,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val idGenerator: () -> String = { "tv-" + clock() + "-" + (counter++) },
) {
    private val _state = MutableStateFlow(CompanionUiState())
    val state: StateFlow<CompanionUiState> = _state.asStateFlow()
    private val work = Mutex()

    init {
        scope.launch { refresh() }
    }

    fun onOpen() {
        locked { if (currentTrusted() != null) runUpkeep(Trigger.APP_OPEN) else refresh() }
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

    /** Removes the network and the TVs saved under it: upkeep would never check them again. */
    fun removeTrustedNetwork(id: String) {
        locked {
            store.tvs().filter { it.trustedNetworkId == id }.forEach { store.removeTv(it.id) }
            store.removeTrustedNetwork(id)
            refresh()
        }
    }

    fun addTvByAddress(address: String) {
        locked { addTv(address.trim()) }
    }

    fun findTvs() {
        locked {
            val trusted = currentTrusted() ?: return@locked refresh(error = Copy.NOT_TRUSTED_ERROR)
            _state.update { it.copy(busy = true, error = null) }
            try {
                val saved = store.tvs().map { it.address }.toSet()
                val hosts = scanner.scan(trusted.fingerprint.subnet, TvConnector.DEFAULT_PORT).filter { it !in saved }
                refresh()
                _state.update { it.copy(found = hosts) }
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
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
        val trusted = currentTrusted() ?: return refresh(error = Copy.NOT_TRUSTED_ERROR)
        // Only addresses on the Trusted Network's own subnet are ever contacted.
        if (!Subnets.containsHost(trusted.fingerprint.subnet, address)) return refresh(error = Copy.OUTSIDE_NETWORK)
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
        if (currentTrusted()?.id != trusted.id) return refresh(error = Copy.NOT_TRUSTED_ERROR)
        if (serial.isBlank()) return refresh(error = Copy.NO_SERIAL)
        val existing = store.tvs().firstOrNull { it.serial == serial }
        store.saveTv(
            SavedTv(
                id = existing?.id ?: idGenerator(),
                name = model.ifBlank { "TV at $address" },
                address = address,
                serial = serial,
                trustedNetworkId = trusted.id,
                isFireTv = maker.equals("Amazon", ignoreCase = true),
            ),
        )
        runUpkeep(Trigger.APP_OPEN)
    }

    private suspend fun refresh(error: String? = null) {
        val fp = fingerprints.current()
        val networks = store.trustedNetworks()
        val trusted = TrustMatcher.currentTrusted(fp, networks)
        val now = clock()
        val tvs = store.tvs()
        val saved = tvs.map { it.address }.toSet()
        val cards = tvs.map { tv ->
            TvCardState(
                id = tv.id,
                name = tv.name,
                address = tv.address,
                statusLabel = Copy.statusLabel(tv.status),
                checkedLabel = tv.lastCheckedEpochMs?.let { checkedAgo(now - it) },
                note = if (tv.isFireTv) null else Copy.NOT_FIRE_TV_NOTE,
            )
        }
        _state.update {
            it.copy(
                loaded = true,
                firstRun = networks.isEmpty(),
                currentNetwork = fp,
                trustedNetworkName = trusted?.name,
                trustedNetworks = networks,
                tvs = cards,
                settings = store.settings(),
                busy = false,
                error = error,
                found = if (trusted == null) emptyList() else it.found.filter { h -> h !in saved },
            )
        }
    }

    private fun checkedAgo(diffMs: Long): String {
        val min = (diffMs / 60_000).coerceAtLeast(0)
        return "Checked $min min ago"
    }

    private fun isIpv4(s: String): Boolean {
        val p = s.split('.')
        return p.size == 4 && p.all { o -> o.isNotEmpty() && o.length <= 3 && o.all { it in '0'..'9' } && o.toInt() <= 255 }
    }

    private companion object {
        var counter = 0
    }
}
