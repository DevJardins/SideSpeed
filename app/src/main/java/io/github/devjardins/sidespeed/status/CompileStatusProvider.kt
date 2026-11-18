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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

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
    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? {
        val pairs = try {
            runBlocking { liveRow() ?: context?.persistedRow() }
        } catch (_: Exception) {
            null
        } ?: return null
        return MatrixCursor(pairs.map { it.first }.toTypedArray()).apply {
            val values: List<Any?> = pairs.map { it.second }
            addRow(values.toTypedArray())
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

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException("read-only")

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("read-only")

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("read-only")

    companion object {
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
