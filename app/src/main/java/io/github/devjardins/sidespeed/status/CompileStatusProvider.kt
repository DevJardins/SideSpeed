package io.github.devjardins.sidespeed.status

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Build
import io.github.devjardins.sidespeed.SideSpeedApp
import io.github.devjardins.sidespeed.data.DataStorePrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Shell-only live compile status, for the Companion's TV card.
 *
 * One row, all strings, empty when absent: `v state pkg label mode started_ms queue
 * last_pkg last_label last_result last_ms self version`. Labels are percent-encoded
 * ([encodeStatusLabel]); see [StatusRow.toWireMap].
 *
 * The provider is exported but gated by `android.permission.DUMP`: the shell holds DUMP,
 * normal third-party apps cannot get it, so only `adb shell content query` (and code
 * running as shell) can read it. There is no write permission on purpose: every write
 * method throws, so the row can never be forged through this surface.
 *
 * The row normally comes from the in-process [CompileStatus] (which restored the
 * persisted in-progress compile as interrupted at startup). Before the app container
 * exists it falls back to the persisted record directly: a stored in-progress compile
 * then reads as `interrupted`, never `compiling`.
 */
class CompileStatusProvider : ContentProvider() {
    /** Latest snapshot; an idle row until the background collector's first pass. Never blocks. */
    @Volatile private var cached: List<Pair<String, String>> = idleRows()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile private var collectorStarted = false

    /** Test seam: replaces the live/persisted row source read by [refreshOnce]. */
    internal var refreshSource: (suspend () -> List<Pair<String, String>>?)? = null

    override fun onCreate(): Boolean {
        ensureCollector()
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? {
        // Served from memory: no runBlocking, no disk, so a binder thread never waits here.
        ensureCollector()
        val pairs = currentRows()
        return MatrixCursor(pairs.map { it.first }.toTypedArray()).apply {
            val values: List<Any?> = pairs.map { it.second }
            addRow(values.toTypedArray())
        }
    }

    /** The snapshot [query] serves: the latest collector pass, or the idle row before the first. */
    internal fun currentRows(): List<Pair<String, String>> = cached

    /** One collector pass; keeps the previous snapshot when the source fails or has no row. */
    internal suspend fun refreshOnce() {
        val rows = try {
            refreshSource?.invoke() ?: liveRow() ?: context?.persistedRow()
        } catch (_: Exception) {
            null
        }
        if (rows != null) cached = rows
    }

    /** Starts the background collector once, from [onCreate] or lazily from [query]. */
    @Synchronized private fun ensureCollector() {
        if (collectorStarted) return
        collectorStarted = true
        scope.launch {
            while (true) {
                delay(STATUS_SNAPSHOT_REFRESH_MS)
                refreshOnce()
            }
        }
    }

    /** The restored in-process status; null before the app container exists. */
    private suspend fun liveRow(): List<Pair<String, String>>? {
        val app = context?.applicationContext as? SideSpeedApp ?: return null
        val status = runCatching { app.container.compileStatus }.getOrNull() ?: return null
        return status.snapshot().toWireMap()
    }

    /** Best-effort row from the persisted record alone, for queries before the container exists. */
    private suspend fun Context.persistedRow(): List<Pair<String, String>> {
        val saved = DataStoreCompileStatusStore(this).load()
        val debuggable = cachedDebuggable() ?: false
        val build = selfBuildIdentity()
        val selfOptimized = try {
            DataStorePrefs(this).selfCompile.first()?.let { record ->
                record.versionCode == build?.first && record.lastUpdateTime == build.second
            } ?: false
        } catch (_: Exception) {
            false
        }
        val self = if (debuggable) SelfState.DEBUG else if (selfOptimized) SelfState.OPTIMIZED else SelfState.PENDING
        val active = saved?.current
        return StatusRow(
            phase = if (active != null) CompilePhase.INTERRUPTED else CompilePhase.IDLE,
            activePkg = active?.pkg ?: "",
            activeLabel = active?.label ?: "",
            activeMode = active?.mode ?: "",
            activeStartedMs = active?.startedMs ?: 0L,
            queueWaiting = 0,
            last = saved?.last,
            self = self,
            version = selfBuildIdentity()?.third ?: "",
        ).toWireMap()
    }

    /**
     * The safe row served before the collector's first pass: idle, nothing in progress,
     * nothing finished, self not yet known, no version. Same wire shape as every other row.
     */
    private fun idleRows(): List<Pair<String, String>> = StatusRow(
        phase = CompilePhase.IDLE,
        activePkg = "",
        activeLabel = "",
        activeMode = "",
        activeStartedMs = 0L,
        queueWaiting = 0,
        last = null,
        self = SelfState.PENDING,
        version = "",
    ).toWireMap()

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException("read-only")

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("read-only")

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("read-only")

    companion object {
        /** Collector cadence: fresh enough for companion polls, cheap enough to run always. */
        const val STATUS_SNAPSHOT_REFRESH_MS = 2_000L

        @Volatile private var debuggableCache: Boolean? = null

        private fun Context.cachedDebuggable(): Boolean? {
            debuggableCache?.let { return it }
            return try {
                val flags = if (Build.VERSION.SDK_INT >= 33) {
                    packageManager.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0)).flags
                } else {
                    @Suppress("DEPRECATION")
                    packageManager.getApplicationInfo(packageName, 0).flags
                }
                (((flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0).also { debuggableCache = it })
            } catch (_: Exception) {
                null
            }
        }

        private fun Context.selfBuildIdentity(): Triple<Long, Long, String>? = try {
            val info = if (Build.VERSION.SDK_INT >= 33) {
                packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(packageName, 0)
            }
            val versionCode = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else {
                @Suppress("DEPRECATION")
                info.versionCode.toLong()
            }
            Triple(versionCode, info.lastUpdateTime, info.versionName ?: "")
        } catch (_: Exception) {
            null
        }
    }
}
