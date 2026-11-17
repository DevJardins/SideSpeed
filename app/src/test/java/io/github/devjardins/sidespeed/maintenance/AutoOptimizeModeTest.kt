package io.github.devjardins.sidespeed.maintenance

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ResolveModeTest {
    @Test fun nothingStoredMeansSelected() = assertEquals(AutoOptimizeMode.SELECTED, resolveMode(null, null))

    @Test fun legacySwitchOnMigratesToAll() = assertEquals(AutoOptimizeMode.ALL, resolveMode(null, true))

    @Test fun legacySwitchOffMigratesToSelected() = assertEquals(AutoOptimizeMode.SELECTED, resolveMode(null, false))

    @Test fun storedModeWinsOverLegacySwitch() {
        assertEquals(AutoOptimizeMode.SIDELOADED, resolveMode("SIDELOADED", true))
        assertEquals(AutoOptimizeMode.SELECTED, resolveMode("SELECTED", true))
    }

    @Test fun unknownStoredValueFallsBackToLegacyThenSelected() {
        assertEquals(AutoOptimizeMode.ALL, resolveMode("FUTURE_MODE", true))
        assertEquals(AutoOptimizeMode.SELECTED, resolveMode("FUTURE_MODE", null))
    }
}

class DataStoreMaintenanceStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun TestScope.dataStore(name: String = "m.preferences_pb"): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(scope = backgroundScope, produceFile = { File(tmp.root, name) })

    @Test fun existingUserWithLegacySwitchOnKeepsOptimizingEverything() = runTest {
        val ds = dataStore()
        ds.edit { it[booleanPreferencesKey("optimize_sideloaded")] = true }
        assertEquals(AutoOptimizeMode.ALL, DataStoreMaintenanceStore(ds).mode.first())
    }

    @Test fun existingUserWithLegacySwitchOffKeepsTickedAppsOnly() = runTest {
        val ds = dataStore()
        ds.edit { it[booleanPreferencesKey("optimize_sideloaded")] = false }
        assertEquals(AutoOptimizeMode.SELECTED, DataStoreMaintenanceStore(ds).mode.first())
    }

    @Test fun freshInstallDefaultsToSelected() = runTest {
        assertEquals(AutoOptimizeMode.SELECTED, DataStoreMaintenanceStore(dataStore()).mode.first())
    }

    @Test fun chosenModePersistsAndBeatsTheLegacyKey() = runTest {
        val ds = dataStore()
        ds.edit { it[booleanPreferencesKey("optimize_sideloaded")] = true }
        val store = DataStoreMaintenanceStore(ds)
        store.setMode(AutoOptimizeMode.SIDELOADED)
        assertEquals(AutoOptimizeMode.SIDELOADED, store.mode.first())
        assertEquals("SIDELOADED", ds.data.first()[stringPreferencesKey("auto_optimize_mode")])
    }

    @Test fun tickedAppsSurviveEveryModeChange() = runTest {
        val store = DataStoreMaintenanceStore(dataStore())
        store.setMaintained("a.b", true)
        AutoOptimizeMode.entries.forEach { store.setMode(it) }
        assertEquals(setOf("a.b"), store.maintained.first())
    }
}
