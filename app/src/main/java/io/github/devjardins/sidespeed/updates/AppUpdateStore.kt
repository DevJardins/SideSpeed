package io.github.devjardins.sidespeed.updates

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** The last good updates.json, its ETag, and when it was last refreshed. */
interface AppUpdateStore {
    val json: Flow<String?>
    val etag: Flow<String?>
    val lastCheckMs: Flow<Long>
    suspend fun setCheck(atMs: Long, json: String?, etag: String?)
}

private val Context.appUpdateDataStore by preferencesDataStore("sidespeed_app_updates")

/** Own DataStore file (`sidespeed_app_updates`), so the existing stores stay untouched. */
class DataStoreAppUpdateStore(private val store: DataStore<Preferences>) : AppUpdateStore {
    constructor(context: Context) : this(context.applicationContext.appUpdateDataStore)

    private val jsonKey = stringPreferencesKey("catalog_json")
    private val etagKey = stringPreferencesKey("catalog_etag")
    private val lastCheckKey = longPreferencesKey("catalog_last_check_ms")

    override val json: Flow<String?> = store.data.map { prefs -> prefs[jsonKey] }

    override val etag: Flow<String?> = store.data.map { prefs -> prefs[etagKey] }

    override val lastCheckMs: Flow<Long> = store.data.map { prefs -> prefs[lastCheckKey] ?: 0L }

    override suspend fun setCheck(atMs: Long, json: String?, etag: String?) {
        store.edit {
            it[lastCheckKey] = atMs
            if (json == null) it.remove(jsonKey) else it[jsonKey] = json
            if (etag == null) it.remove(etagKey) else it[etagKey] = etag
        }
    }
}
