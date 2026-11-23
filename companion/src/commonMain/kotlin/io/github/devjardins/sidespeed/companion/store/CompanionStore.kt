package io.github.devjardins.sidespeed.companion.store

import io.github.devjardins.sidespeed.companion.net.TrustedNetwork
import io.github.devjardins.sidespeed.companion.net.TrustedOverlay

enum class TvStatus { RUNNING, NOT_RUNNING, OFFLINE, WAITING_FOR_ALLOW, NOT_INSTALLED, WRONG_DEVICE, START_FAILED, UNKNOWN }

/**
 * How the last check reached a TV: on the home network (a LAN address), over Tailscale (a 100.64.0.0/10
 * address), or not at all. Null when the TV was never checked.
 */
enum class Reach { REACHED_LAN, REACHED_TAILNET, UNREACHABLE }

/**
 * A TV the user added. [serial] is the TV's `ro.serialno` recorded at add time; it is never blank, because the
 * companion sends nothing beyond `getprop ro.serialno` to a device whose serial doesn't match it.
 *
 * [offlineCount] counts consecutive failed checks; [skipBackgroundUntilUserAction] is set while the TV's
 * "Allow USB debugging?" prompt is waiting, so background checks don't pop it again.
 */
data class SavedTv(
    val id: String,
    val name: String,
    val address: String,
    val serial: String,
    val trustedNetworkId: String,
    val status: TvStatus = TvStatus.UNKNOWN,
    val lastCheckedEpochMs: Long? = null,
    val offlineCount: Int = 0,
    val lastRefindEpochMs: Long? = null,
    val skipBackgroundUntilUserAction: Boolean = false,
    /** False when the TV's manufacturer isn't Amazon: SideSpeed there handles setup itself (the spec's Google TV note). */
    val isFireTv: Boolean = true,
    /**
     * For a TV on a [TrustedOverlay]: the Tailscale node ID it is bound to. The address may change (and is then
     * updated once the serial matches); the node may not. Null for a TV on a Trusted Network.
     */
    val overlayPeerId: String? = null,
    /** How the last check reached this TV; null when it was never checked. */
    val reach: Reach? = null,
) {
    companion object {
        /** @throws IllegalArgumentException if [tv] has a blank id or serial. */
        fun requireValid(tv: SavedTv) {
            require(tv.id.isNotBlank()) { "A saved TV needs an id" }
            require(tv.serial.isNotBlank()) { "A saved TV needs its serial" }
        }
    }
}

/**
 * Notifications, "Start when I log in" and the Wi-Fi name are off by default; checks run every 15 minutes
 * (option: 60). [useWifiName] is the opt-in to recognise a Trusted Network by its Wi-Fi name and access
 * point on top of the router (Android only; it needs location permission to read them).
 */
data class CompanionSettings(
    val notifications: Boolean = false,
    val intervalMinutes: Int = 15,
    val startAtLogin: Boolean = false,
    val useWifiName: Boolean = false,
)

/**
 * A device on [trustedNetworkId] that answered a re-find connection with its "Allow USB debugging?" prompt.
 * Background re-finds skip it for [PROMPTED_HOST_TTL_MS] so a foreign device is bothered at most once per
 * offline episode.
 */
data class PromptedHost(val trustedNetworkId: String, val address: String, val atEpochMs: Long) {
    /** Recorded less than [PROMPTED_HOST_TTL_MS] ago. A timestamp in the future (clock moved back) has expired. */
    fun isFresh(now: Long): Boolean = now - atEpochMs in 0 until PROMPTED_HOST_TTL_MS

    companion object {
        const val PROMPTED_HOST_TTL_MS = 24 * 60 * 60 * 1000L
    }
}

/** Saved TVs, Trusted Networks and settings. Saving an existing id replaces it. */
interface CompanionStore {
    /**
     * True when the loaded file was saved by a newer copy of the companion (its `version` is greater
     * than the version this build writes). The store is then read-only: every save is a no-op that
     * leaves the file byte-for-byte unchanged, so an older build can never overwrite what a newer one
     * saved. The UI shows a persistent banner (see `Copy.OLDER_COPY`) instead of the edits it drops.
     */
    val savedByNewerVersion: Boolean

    suspend fun tvs(): List<SavedTv>

    /**
     * The user's add or edit of a TV. Being a user action, it also clears [promptedHosts].
     * @throws IllegalArgumentException for a blank id or serial (see [SavedTv.requireValid]).
     */
    suspend fun saveTv(tv: SavedTv)

    /**
     * Re-reads TV [id] under the store's lock and saves `transform(current)`. Returns false and saves nothing if
     * the TV no longer exists. Used by upkeep, so a long check never brings back a removed TV or undoes an edit.
     * @throws IllegalArgumentException if the result is invalid or changes the id.
     */
    suspend fun updateTv(id: String, transform: (SavedTv) -> SavedTv): Boolean
    suspend fun removeTv(id: String)

    suspend fun promptedHosts(): List<PromptedHost>
    suspend fun savePromptedHosts(hosts: List<PromptedHost>)

    suspend fun trustedNetworks(): List<TrustedNetwork>
    suspend fun saveTrustedNetwork(n: TrustedNetwork)
    suspend fun removeTrustedNetwork(id: String)

    suspend fun trustedOverlays(): List<TrustedOverlay>
    suspend fun saveTrustedOverlay(o: TrustedOverlay)
    suspend fun removeTrustedOverlay(id: String)

    suspend fun settings(): CompanionSettings
    suspend fun saveSettings(s: CompanionSettings)
}
