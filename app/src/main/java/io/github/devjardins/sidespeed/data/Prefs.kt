package io.github.devjardins.sidespeed.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.devjardins.sidespeed.compile.CompileMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

data class LastCompile(val epochMs: Long, val mode: CompileMode)

interface Prefs {
    val lanWarningAcked: Flow<Boolean>
    suspend fun setLanWarningAcked(v: Boolean)
    val pairedTls: Flow<Boolean>
    suspend fun setPairedTls(v: Boolean)
    fun lastCompile(pkg: String): Flow<LastCompile?>
    suspend fun setLastCompile(pkg: String, value: LastCompile)
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

    private fun lastKey(pkg: String) = stringPreferencesKey("last_compile_$pkg")

    override fun lastCompile(pkg: String): Flow<LastCompile?> = store.data.map { prefs ->
        prefs[lastKey(pkg)]?.let { raw ->
            val (ms, arg) = raw.split("|", limit = 2).takeIf { it.size == 2 } ?: return@let null
            val mode = CompileMode.entries.firstOrNull { it.arg == arg } ?: return@let null
            ms.toLongOrNull()?.let { LastCompile(it, mode) }
        }
    }

    override suspend fun setLastCompile(pkg: String, value: LastCompile) {
        store.edit { it[lastKey(pkg)] = "${value.epochMs}|${value.mode.arg}" }
    }
}
