package io.github.devjardins.sidespeed.maintenance

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.devjardins.sidespeed.tools.CleanupThreshold
import io.github.devjardins.sidespeed.tools.ToolsStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * What the auto-optimize engine keeps compiled. Apps the user ticked are kept in every mode: a tick is an explicit
 * request (the Compile screen's "Keep this app optimized" must stay true), and changing mode never erases the ticks.
 */
enum class AutoOptimizeMode {
    /** Only the ticked apps. */
    SELECTED,
    /** Apps not installed from a store, plus the ticked ones. */
    SIDELOADED,
    /** Every app that is not preinstalled, plus the ticked ones. */
    ALL,
}

/** The stored mode; without one, the pre-mode boolean `optimize_sideloaded` (which meant "all") keeps its behavior. */
fun resolveMode(stored: String?, legacyAll: Boolean?): AutoOptimizeMode =
    AutoOptimizeMode.entries.firstOrNull { it.name == stored }
        ?: if (legacyAll == true) AutoOptimizeMode.ALL else AutoOptimizeMode.SELECTED

/** True when anything is maintained: a ticked app, or a mode that covers apps by itself. The service runs only then. */
fun isArmed(mode: AutoOptimizeMode, ticked: Set<String>): Boolean = mode != AutoOptimizeMode.SELECTED || ticked.isNotEmpty()

/**
 * True when the service must stay running: compile maintenance armed, automatic
 * cleanup set, or a launch measurement in flight ([measuring]). The service
 * stops itself again once the sequence ends.
 */
fun isServiceNeeded(
    mode: AutoOptimizeMode,
    ticked: Set<String>,
    threshold: CleanupThreshold,
    measuring: Boolean = false,
): Boolean = isArmed(mode, ticked) || threshold != CleanupThreshold.OFF || measuring

interface MaintenanceStore {
    /** Packages the user ticked. */
    val maintained: Flow<Set<String>>
    suspend fun setMaintained(pkg: String, on: Boolean)

    /** Which apps are kept optimized besides the ticked ones; [AutoOptimizeMode.SELECTED] by default. */
    val mode: Flow<AutoOptimizeMode>
    suspend fun setMode(mode: AutoOptimizeMode)

    val history: Flow<List<JobRecord>>
    suspend fun addHistory(record: JobRecord)
}

private val Context.maintenanceDataStore by preferencesDataStore("sidespeed_maintenance")

/** Own DataStore file, so the existing `Prefs` interface and its test fakes stay untouched. */
class DataStoreMaintenanceStore(private val store: DataStore<Preferences>) : MaintenanceStore {
    constructor(context: Context) : this(context.applicationContext.maintenanceDataStore)

    private val maintainedKey = stringSetPreferencesKey("maintained")
    private val legacyAllKey = booleanPreferencesKey("optimize_sideloaded")
    private val modeKey = stringPreferencesKey("auto_optimize_mode")
    private val historyKey = stringPreferencesKey("history")

    override val maintained: Flow<Set<String>> = store.data.map { it[maintainedKey] ?: emptySet() }
    override suspend fun setMaintained(pkg: String, on: Boolean) {
        store.edit { it[maintainedKey] = (it[maintainedKey] ?: emptySet()).let { s -> if (on) s + pkg else s - pkg } }
    }

    override val mode: Flow<AutoOptimizeMode> = store.data.map { resolveMode(it[modeKey], it[legacyAllKey]) }
    override suspend fun setMode(mode: AutoOptimizeMode) { store.edit { it[modeKey] = mode.name } }

    override val history: Flow<List<JobRecord>> = store.data.map { decodeHistory(it[historyKey] ?: "") }
    override suspend fun addHistory(record: JobRecord) {
        store.edit { it[historyKey] = encodeHistory(appendHistory(decodeHistory(it[historyKey] ?: ""), record)) }
    }
}

suspend fun isArmed(store: MaintenanceStore): Boolean = isArmed(store.mode.first(), store.maintained.first())

suspend fun isServiceNeeded(maintenance: MaintenanceStore, tools: ToolsStore, measuring: Boolean = false): Boolean =
    isServiceNeeded(maintenance.mode.first(), maintenance.maintained.first(), tools.cleanupThreshold.first(), measuring)
