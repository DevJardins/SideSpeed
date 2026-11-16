package io.github.devjardins.sidespeed.companion.store

import io.github.devjardins.sidespeed.companion.net.TrustedNetwork

enum class TvStatus { RUNNING, NOT_RUNNING, OFFLINE, WAITING_FOR_ALLOW, NOT_INSTALLED, WRONG_DEVICE, START_FAILED, UNKNOWN }

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
) {
    companion object {
        /** @throws IllegalArgumentException if [tv] has a blank id or serial. */
        fun requireValid(tv: SavedTv) {
            require(tv.id.isNotBlank()) { "A saved TV needs an id" }
            require(tv.serial.isNotBlank()) { "A saved TV needs its serial" }
        }
    }
}

/** Notifications and "Start when I log in" are off by default; checks run every 15 minutes (option: 60). */
data class CompanionSettings(
    val notifications: Boolean = false,
    val intervalMinutes: Int = 15,
    val startAtLogin: Boolean = false,
)

/** Saved TVs, Trusted Networks and settings. Saving an existing id replaces it. */
interface CompanionStore {
    suspend fun tvs(): List<SavedTv>

    /** @throws IllegalArgumentException for a blank id or serial (see [SavedTv.requireValid]). */
    suspend fun saveTv(tv: SavedTv)
    suspend fun removeTv(id: String)

    suspend fun trustedNetworks(): List<TrustedNetwork>
    suspend fun saveTrustedNetwork(n: TrustedNetwork)
    suspend fun removeTrustedNetwork(id: String)

    suspend fun settings(): CompanionSettings
    suspend fun saveSettings(s: CompanionSettings)
}
