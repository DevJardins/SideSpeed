package io.github.devjardins.sidespeed.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

interface Prefs {
    val lanWarningAcked: Flow<Boolean>
    suspend fun setLanWarningAcked(v: Boolean)
    val pairedTls: Flow<Boolean>
    suspend fun setPairedTls(v: Boolean)
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
}
