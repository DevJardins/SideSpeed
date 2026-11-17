package io.github.devjardins.sidespeed.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.devjardins.sidespeed.compile.CompileMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

data class LastCompile(val epochMs: Long, val mode: CompileMode)

/** SideSpeed's own compile, recorded so it runs once per install/update. */
data class SelfCompile(val versionCode: Long, val lastUpdateTime: Long)

internal const val LAST_COMPILE_PREFIX = "last_compile_"

/** Parses a stored `"<epochMs>|<mode>"` record, or null when it is missing or malformed. */
internal fun parseLastCompile(raw: String?): LastCompile? {
    val parts = raw?.split("|", limit = 2)?.takeIf { it.size == 2 } ?: return null
    val mode = CompileMode.entries.firstOrNull { it.arg == parts[1] } ?: return null
    val ms = parts[0].toLongOrNull() ?: return null
    return LastCompile(ms, mode)
}

interface Prefs {
    val lanWarningAcked: Flow<Boolean>
    suspend fun setLanWarningAcked(v: Boolean)
    val pairedTls: Flow<Boolean>
    suspend fun setPairedTls(v: Boolean)
    fun lastCompile(pkg: String): Flow<LastCompile?>
    suspend fun setLastCompile(pkg: String, value: LastCompile)
    /** Every stored last-compile record, keyed by package; lets the app list collect once instead of per row. */
    fun lastCompiles(): Flow<Map<String, LastCompile>>
    val selfCompile: Flow<SelfCompile?>
    suspend fun setSelfCompile(v: SelfCompile)
}

private val Context.dataStore by preferencesDataStore("sidespeed_prefs")

class DataStorePrefs(context: Context) : Prefs {
    private val store = context.applicationContext.dataStore
    private val lanKey = booleanPreferencesKey("lan_warning_acked")
    private val tlsKey = booleanPreferencesKey("paired_tls")

    override val lanWarningAcked: Flow<Boolean> = store.data.map { it[lanKey] ?: false }
    override suspend fun setLanWarningAcked(v: Boolean) { store.edit { it[lanKey] = v } }
    override val pairedTls: Flow<Boolean> = store.data.map { it[tlsKey] ?: false }
    override suspend fun setPairedTls(v: Boolean) { store.edit { it[tlsKey] = v } }

    private fun lastKey(pkg: String) = stringPreferencesKey("$LAST_COMPILE_PREFIX$pkg")
    private val selfVersionKey = longPreferencesKey("self_compile_version")
    private val selfUpdatedKey = longPreferencesKey("self_compile_updated")

    override fun lastCompile(pkg: String): Flow<LastCompile?> =
        store.data.map { prefs -> parseLastCompile(prefs[lastKey(pkg)]) }

    override suspend fun setLastCompile(pkg: String, value: LastCompile) {
        store.edit { it[lastKey(pkg)] = "${value.epochMs}|${value.mode.arg}" }
    }

    override fun lastCompiles(): Flow<Map<String, LastCompile>> = store.data.map { prefs ->
        buildMap {
            for ((key, value) in prefs.asMap()) {
                if (key.name.startsWith(LAST_COMPILE_PREFIX) && value is String) {
                    parseLastCompile(value)?.let { put(key.name.removePrefix(LAST_COMPILE_PREFIX), it) }
                }
            }
        }
    }

    override val selfCompile: Flow<SelfCompile?> = store.data.map { prefs ->
        val version = prefs[selfVersionKey]
        val updated = prefs[selfUpdatedKey]
        if (version == null || updated == null) null else SelfCompile(version, updated)
    }

    override suspend fun setSelfCompile(v: SelfCompile) {
        store.edit {
            it[selfVersionKey] = v.versionCode
            it[selfUpdatedKey] = v.lastUpdateTime
        }
    }
}
