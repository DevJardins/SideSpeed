package io.github.devjardins.sidespeed.maintenance

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.devjardins.sidespeed.helper.TrustedBssids
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.security.MessageDigest

enum class RecoveryReason {
    NEW_NETWORK,
    LOCATION_OFF,
    NO_PERMISSION,
    NOT_ON_WIFI,
    PROMPTED,
    WRITE_REFUSED,
    ADB_FAILED,
}

sealed interface RecoveryOutcome {
    val epochMs: Long

    data class StartedAgain(override val epochMs: Long) : RecoveryOutcome
    data class Failed(val reason: RecoveryReason, override val epochMs: Long) : RecoveryOutcome
}

/**
 * Computes the 64-character lowercase hex SHA-256 of a validated, normalized lowercase BSSID.
 * Rejects placeholder/sentinel MACs and malformed inputs.
 */
fun sha256HexLower(bssid: String): String {
    val normalized = TrustedBssids.normalizeBssid(bssid)
        ?: throw IllegalArgumentException("invalid or sentinel BSSID")
    val digest = MessageDigest.getInstance("SHA-256").digest(normalized.toByteArray(Charsets.UTF_8))
    val chars = CharArray(digest.size * 2)
    val hex = "0123456789abcdef"
    for (i in digest.indices) {
        val v = digest[i].toInt() and 0xFF
        chars[i * 2] = hex[v ushr 4]
        chars[i * 2 + 1] = hex[v and 0x0F]
    }
    return String(chars)
}

internal fun encodeRecoveryOutcome(o: RecoveryOutcome): String = when (o) {
    is RecoveryOutcome.StartedAgain -> "ok|${o.epochMs}"
    is RecoveryOutcome.Failed -> "fail|${o.reason.name}|${o.epochMs}"
}

internal fun parseRecoveryOutcome(raw: String?): RecoveryOutcome? {
    if (raw.isNullOrEmpty()) return null
    val parts = raw.split('|')
    return when {
        parts.size == 2 && parts[0] == "ok" -> {
            val ts = parts[1].toLongOrNull() ?: return null
            RecoveryOutcome.StartedAgain(ts)
        }
        parts.size == 3 && parts[0] == "fail" -> {
            val reason = RecoveryReason.entries.firstOrNull { it.name == parts[1] } ?: return null
            val ts = parts[2].toLongOrNull() ?: return null
            RecoveryOutcome.Failed(reason, ts)
        }
        else -> null
    }
}

interface RebootRecoveryStore {
    val enabled: Flow<Boolean>
    suspend fun setEnabled(v: Boolean)

    val allowedBssidHashes: Flow<Set<String>>
    suspend fun addAllowedBssidHashes(hashes: Set<String>)
    suspend fun clearAllowedBssidHashes()

    val pendingRevokes: Flow<Boolean>
    suspend fun setPendingRevokes(v: Boolean)

    val lastRecovery: Flow<RecoveryOutcome?>
    suspend fun setLastRecovery(v: RecoveryOutcome?)

    val dailyRefreshFailed: Flow<Boolean>
    suspend fun setDailyRefreshFailed(v: Boolean)
}

private val Context.rebootRecoveryDataStore by preferencesDataStore("sidespeed_reboot_recovery")

/** Dedicated DataStore file for reboot recovery state, leaving `Prefs` and its test fakes untouched. */
class DataStoreRebootRecoveryStore(private val store: DataStore<Preferences>) : RebootRecoveryStore {
    constructor(context: Context) : this(context.applicationContext.rebootRecoveryDataStore)

    private val enabledKey = booleanPreferencesKey("enabled")
    private val allowedHashesKey = stringSetPreferencesKey("allowed_bssid_hashes")
    private val pendingRevokesKey = booleanPreferencesKey("pending_revokes")
    private val lastRecoveryKey = stringPreferencesKey("last_recovery")
    private val dailyRefreshFailedKey = booleanPreferencesKey("daily_refresh_failed")

    override val enabled: Flow<Boolean> = store.data.map { it[enabledKey] ?: false }
    override suspend fun setEnabled(v: Boolean) {
        store.edit { it[enabledKey] = v }
    }

    override val allowedBssidHashes: Flow<Set<String>> = store.data.map { it[allowedHashesKey] ?: emptySet() }
    override suspend fun addAllowedBssidHashes(hashes: Set<String>) {
        if (hashes.isEmpty()) return
        store.edit { it[allowedHashesKey] = (it[allowedHashesKey] ?: emptySet()) + hashes }
    }

    override suspend fun clearAllowedBssidHashes() {
        store.edit { it.remove(allowedHashesKey) }
    }

    override val pendingRevokes: Flow<Boolean> = store.data.map { it[pendingRevokesKey] ?: false }
    override suspend fun setPendingRevokes(v: Boolean) {
        store.edit { it[pendingRevokesKey] = v }
    }

    override val lastRecovery: Flow<RecoveryOutcome?> = store.data.map { parseRecoveryOutcome(it[lastRecoveryKey]) }
    override suspend fun setLastRecovery(v: RecoveryOutcome?) {
        store.edit {
            if (v == null) it.remove(lastRecoveryKey) else it[lastRecoveryKey] = encodeRecoveryOutcome(v)
        }
    }

    override val dailyRefreshFailed: Flow<Boolean> = store.data.map { it[dailyRefreshFailedKey] ?: false }
    override suspend fun setDailyRefreshFailed(v: Boolean) {
        store.edit { it[dailyRefreshFailedKey] = v }
    }
}
