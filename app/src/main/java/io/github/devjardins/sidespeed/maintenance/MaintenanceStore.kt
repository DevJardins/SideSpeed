package io.github.devjardins.sidespeed.maintenance

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

interface MaintenanceStore {
    /** Packages the user ticked. */
    val maintained: Flow<Set<String>>
    suspend fun setMaintained(pkg: String, on: Boolean)

    /** "Also optimize apps not from a store"; off by default. */
    val optimizeSideloaded: Flow<Boolean>
    suspend fun setOptimizeSideloaded(on: Boolean)

    val history: Flow<List<JobRecord>>
    suspend fun addHistory(record: JobRecord)
}

private val Context.maintenanceDataStore by preferencesDataStore("sidespeed_maintenance")

/** Own DataStore file, so the existing `Prefs` interface and its test fakes stay untouched. */
class DataStoreMaintenanceStore(context: Context) : MaintenanceStore {
    private val store = context.applicationContext.maintenanceDataStore
    private val maintainedKey = stringSetPreferencesKey("maintained")
    private val sideloadedKey = booleanPreferencesKey("optimize_sideloaded")
    private val historyKey = stringPreferencesKey("history")

    override val maintained: Flow<Set<String>> = store.data.map { it[maintainedKey] ?: emptySet() }
    override suspend fun setMaintained(pkg: String, on: Boolean) {
        store.edit { it[maintainedKey] = (it[maintainedKey] ?: emptySet()).let { s -> if (on) s + pkg else s - pkg } }
    }

    override val optimizeSideloaded: Flow<Boolean> = store.data.map { it[sideloadedKey] ?: false }
    override suspend fun setOptimizeSideloaded(on: Boolean) { store.edit { it[sideloadedKey] = on } }

    override val history: Flow<List<JobRecord>> = store.data.map { decodeHistory(it[historyKey] ?: "") }
    override suspend fun addHistory(record: JobRecord) {
        store.edit { it[historyKey] = encodeHistory(appendHistory(decodeHistory(it[historyKey] ?: ""), record)) }
    }
}
