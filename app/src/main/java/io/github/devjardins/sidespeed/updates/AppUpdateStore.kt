package io.github.devjardins.sidespeed.updates

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * The last good updates.json, its ETag, and when it was last refreshed.
 *
 * The body lives in a plain file (it can approach 1 MB); the [DataStore]
 * holds only the ETag and the last check time, so small-preference reads
 * never pay for the catalog. Implementations stay behind this interface so
 * tests can keep using a fake.
 */
interface AppUpdateStore {
    val json: Flow<String?>
    val etag: Flow<String?>
    val lastCheckMs: Flow<Long>
    suspend fun setCheck(atMs: Long, json: String?, etag: String?)
}

private val Context.appUpdateDataStore by preferencesDataStore("sidespeed_app_updates")

/** File in [filesDir] holding the last good catalog body. */
internal const val APP_UPDATE_BODY_FILE = "sidespeed_app_updates.json"

/** Own DataStore file (`sidespeed_app_updates`), so the existing stores stay untouched. */
class DataStoreAppUpdateStore(
    private val store: DataStore<Preferences>,
    private val bodyFile: File,
) : AppUpdateStore {
    constructor(context: Context) : this(
        context.applicationContext.appUpdateDataStore,
        File(context.applicationContext.filesDir, APP_UPDATE_BODY_FILE),
    )

    private val etagKey = stringPreferencesKey("catalog_etag")
    private val lastCheckKey = longPreferencesKey("catalog_last_check_ms")
    private val legacyJsonKey = stringPreferencesKey("catalog_json")

    override val json: Flow<String?> = store.data.map { readBody() }.flowOn(Dispatchers.IO)

    override val etag: Flow<String?> = store.data.map { prefs -> prefs[etagKey] }

    override val lastCheckMs: Flow<Long> = store.data.map { prefs -> prefs[lastCheckKey] ?: 0L }

    override suspend fun setCheck(atMs: Long, json: String?, etag: String?) {
        withContext(Dispatchers.IO) {
            if (json == null) {
                runCatching { bodyFile.delete() }
            } else {
                runCatching { writeBodyAtomically(json) }
            }
        }
        store.edit {
            it[lastCheckKey] = atMs
            if (etag == null) it.remove(etagKey) else it[etagKey] = etag
            // The body used to live here; drop it on the first write after the move.
            it.remove(legacyJsonKey)
        }
    }

    private fun readBody(): String? =
        runCatching { if (bodyFile.isFile) bodyFile.readText(Charsets.UTF_8) else null }.getOrNull()

    /** Write to a temp file, then rename, so readers never see a half-written catalog. */
    private fun writeBodyAtomically(body: String) {
        val parent = bodyFile.parentFile ?: return
        val tmp = File(parent, "${bodyFile.name}.tmp")
        tmp.writeText(body, Charsets.UTF_8)
        if (!tmp.renameTo(bodyFile)) {
            tmp.copyTo(bodyFile, overwrite = true)
            tmp.delete()
        }
    }
}
