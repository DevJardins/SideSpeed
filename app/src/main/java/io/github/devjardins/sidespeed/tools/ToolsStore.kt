package io.github.devjardins.sidespeed.tools

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.devjardins.sidespeed.compile.CompileMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

enum class CleanupThreshold(val bytes: Long?) {
    OFF(null),
    MB_500(500_000_000),
    GB_1(1_000_000_000),
    GB_2(2_000_000_000),
}

data class CleanupRecord(val atMs: Long, val freedBytes: Long?, val skipped: String?)

data class LaunchResult(
    val pkg: String,
    val versionCode: Long,
    val lastUpdateTime: Long,
    val beforeMs: Long?,
    val afterMs: Long,
    val atMs: Long,
)

data class AnimationOriginals(val window: String?, val transition: String?, val animator: String?)

/**
 * An app left in `verify` by an interrupted launch measurement: recompile it
 * to [mode] on the next start, service loop, or helper-up. [versionCode] is
 * provenance (which install was reset), not a match key.
 */
data class RestorePending(val pkg: String, val mode: CompileMode, val versionCode: Long)

interface ToolsStore {
    val cleanupThreshold: Flow<CleanupThreshold>
    suspend fun setCleanupThreshold(t: CleanupThreshold)
    val lastCleanup: Flow<CleanupRecord?>
    suspend fun setLastCleanup(r: CleanupRecord)
    fun launchResult(pkg: String): Flow<LaunchResult?>
    fun launchResults(): Flow<Map<String, LaunchResult>>
    suspend fun setLaunchResult(r: LaunchResult)
    val animationOriginals: Flow<AnimationOriginals?>
    suspend fun setAnimationOriginals(o: AnimationOriginals?)
    val restorePending: Flow<RestorePending?>
    suspend fun setRestorePending(r: RestorePending?)
    val updateDismissedVersion: Flow<String?>
    suspend fun setUpdateDismissedVersion(v: String)
    suspend fun clearUpdateDismissedVersion()
    val updateLastCheckMs: Flow<Long>
    val updateLatestVersion: Flow<String?>
    suspend fun setUpdateCheck(atMs: Long, latest: String?)
    /** The original Home (`pkg/cls`) recorded before SideSpeed's first Home change, or null. */
    val originalHome: Flow<String?>
    suspend fun setOriginalHome(v: String?)
}

/** `"<atMs>|<freedBytes>|<skipped>"`; empty middle/last fields decode as null. */
internal fun encodeCleanupRecord(r: CleanupRecord): String =
    "${r.atMs}|${r.freedBytes?.toString().orEmpty()}|${r.skipped.orEmpty()}"

internal fun parseCleanupRecord(raw: String?): CleanupRecord? {
    val parts = raw?.split("|", limit = 3)?.takeIf { it.size == 3 } ?: return null
    val atMs = parts[0].toLongOrNull() ?: return null
    val freed = parts[1].ifEmpty { null }?.let { it.toLongOrNull() ?: return null }
    return CleanupRecord(atMs = atMs, freedBytes = freed, skipped = parts[2].ifEmpty { null })
}

/** `"<versionCode>|<lastUpdateTime>|<beforeMs>|<afterMs>|<atMs>"`; empty before decodes as null. */
internal fun encodeLaunchResult(r: LaunchResult): String =
    "${r.versionCode}|${r.lastUpdateTime}|${r.beforeMs?.toString().orEmpty()}|${r.afterMs}|${r.atMs}"

internal fun parseLaunchResult(pkg: String, raw: String?): LaunchResult? {
    val parts = raw?.split("|", limit = 5)?.takeIf { it.size == 5 } ?: return null
    return LaunchResult(
        pkg = pkg,
        versionCode = parts[0].toLongOrNull() ?: return null,
        lastUpdateTime = parts[1].toLongOrNull() ?: return null,
        beforeMs = parts[2].ifEmpty { null }?.let { it.toLongOrNull() ?: return null },
        afterMs = parts[3].toLongOrNull() ?: return null,
        atMs = parts[4].toLongOrNull() ?: return null,
    )
}

/** `"<pkg>|<mode-arg>|<versionCode>"`; unknown modes decode as null. */
internal fun encodeRestorePending(r: RestorePending): String =
    "${r.pkg}|${r.mode.arg}|${r.versionCode}"

internal fun parseRestorePending(raw: String?): RestorePending? {
    val parts = raw?.split("|", limit = 3)?.takeIf { it.size == 3 } ?: return null
    return RestorePending(
        pkg = parts[0].takeIf { it.isNotEmpty() } ?: return null,
        mode = CompileMode.entries.firstOrNull { it.arg == parts[1] } ?: return null,
        versionCode = parts[2].toLongOrNull() ?: return null,
    )
}

/** `"<window>|<transition>|<animator>"`; empty fields decode as null. */
internal fun encodeAnimationOriginals(o: AnimationOriginals): String =
    "${o.window.orEmpty()}|${o.transition.orEmpty()}|${o.animator.orEmpty()}"

internal fun parseAnimationOriginals(raw: String?): AnimationOriginals? {
    val parts = raw?.split("|", limit = 3)?.takeIf { it.size == 3 } ?: return null
    return AnimationOriginals(
        window = parts[0].ifEmpty { null },
        transition = parts[1].ifEmpty { null },
        animator = parts[2].ifEmpty { null },
    )
}

private const val LAUNCH_PREFIX = "launch_"

private val Context.toolsDataStore by preferencesDataStore("sidespeed_tools")

/** Own DataStore file (`sidespeed_tools`), so the existing `Prefs` store stays untouched. */
class DataStoreToolsStore(private val store: DataStore<Preferences>) : ToolsStore {
    constructor(context: Context) : this(context.applicationContext.toolsDataStore)

    private val thresholdKey = stringPreferencesKey("cleanup_threshold")
    private val restorePendingKey = stringPreferencesKey("restore_pending")
    private val lastCleanupKey = stringPreferencesKey("last_cleanup")
    private val originalsKey = stringPreferencesKey("animation_originals")
    private val dismissedKey = stringPreferencesKey("update_dismissed_version")
    private val lastCheckKey = longPreferencesKey("update_last_check_ms")
    private val latestKey = stringPreferencesKey("update_latest_version")
    private val originalHomeKey = stringPreferencesKey("original_home")

    private fun launchKey(pkg: String) = stringPreferencesKey("$LAUNCH_PREFIX$pkg")

    override val cleanupThreshold: Flow<CleanupThreshold> = store.data.map { prefs ->
        prefs[thresholdKey]?.let { raw -> CleanupThreshold.entries.firstOrNull { it.name == raw } }
            ?: CleanupThreshold.OFF
    }

    override suspend fun setCleanupThreshold(t: CleanupThreshold) {
        store.edit { it[thresholdKey] = t.name }
    }

    override val lastCleanup: Flow<CleanupRecord?> = store.data.map { prefs ->
        parseCleanupRecord(prefs[lastCleanupKey])
    }

    override suspend fun setLastCleanup(r: CleanupRecord) {
        store.edit { it[lastCleanupKey] = encodeCleanupRecord(r) }
    }

    override fun launchResult(pkg: String): Flow<LaunchResult?> = store.data.map { prefs ->
        parseLaunchResult(pkg, prefs[launchKey(pkg)])
    }

    override fun launchResults(): Flow<Map<String, LaunchResult>> = store.data.map { prefs ->
        buildMap {
            for ((key, value) in prefs.asMap()) {
                if (key.name.startsWith(LAUNCH_PREFIX) && value is String) {
                    val pkg = key.name.removePrefix(LAUNCH_PREFIX)
                    parseLaunchResult(pkg, value)?.let { put(pkg, it) }
                }
            }
        }
    }

    override suspend fun setLaunchResult(r: LaunchResult) {
        store.edit { it[launchKey(r.pkg)] = encodeLaunchResult(r) }
    }

    override val animationOriginals: Flow<AnimationOriginals?> = store.data.map { prefs ->
        prefs[originalsKey]?.let(::parseAnimationOriginals)
    }

    override suspend fun setAnimationOriginals(o: AnimationOriginals?) {
        store.edit {
            if (o == null) it.remove(originalsKey) else it[originalsKey] = encodeAnimationOriginals(o)
        }
    }

    override val restorePending: Flow<RestorePending?> = store.data.map { prefs ->
        prefs[restorePendingKey]?.let(::parseRestorePending)
    }

    override suspend fun setRestorePending(r: RestorePending?) {
        store.edit {
            if (r == null) it.remove(restorePendingKey) else it[restorePendingKey] = encodeRestorePending(r)
        }
    }

    override val updateDismissedVersion: Flow<String?> = store.data.map { prefs -> prefs[dismissedKey] }

    override suspend fun setUpdateDismissedVersion(v: String) {
        store.edit { it[dismissedKey] = v }
    }

    override suspend fun clearUpdateDismissedVersion() {
        store.edit { it.remove(dismissedKey) }
    }

    override val updateLastCheckMs: Flow<Long> = store.data.map { prefs -> prefs[lastCheckKey] ?: 0L }

    override val updateLatestVersion: Flow<String?> = store.data.map { prefs -> prefs[latestKey] }

    override suspend fun setUpdateCheck(atMs: Long, latest: String?) {
        store.edit {
            it[lastCheckKey] = atMs
            if (latest == null) it.remove(latestKey) else it[latestKey] = latest
        }
    }

    override val originalHome: Flow<String?> = store.data.map { prefs -> prefs[originalHomeKey] }

    override suspend fun setOriginalHome(v: String?) {
        store.edit {
            if (v == null) it.remove(originalHomeKey) else it[originalHomeKey] = v
        }
    }
}
