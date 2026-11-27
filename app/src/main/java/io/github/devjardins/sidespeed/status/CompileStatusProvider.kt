package io.github.devjardins.sidespeed.status

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.Bundle
import io.github.devjardins.sidespeed.AppContainer
import io.github.devjardins.sidespeed.SideSpeedApp
import io.github.devjardins.sidespeed.compile.PackageName
import io.github.devjardins.sidespeed.data.DataStorePrefs
import io.github.devjardins.sidespeed.maintenance.MaintenanceService
import io.github.devjardins.sidespeed.maintenance.RemoteCompileJob
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

/** Result of a shell-only [CompileStatusProvider.call] dispatch. */
data class RemoteCallResult(
    val ok: Boolean,
    val queued: Int = 0,
    val reqSeq: Long = 0L,
    val error: String? = null,
)

/**
 * Shell-only live compile status and remote compile trigger endpoint, for the Companion's TV card.
 *
 * One row of 16 columns, all strings, empty when absent: `v state pkg label mode started_ms queue
 * last_pkg last_label last_result last_ms self version req_seq remote retrying`. Labels are
 * percent-encoded ([encodeStatusLabel]); see [StatusRow.toWireMap].
 *
 * The provider is exported so ADB shell can reach it:
 * - [query] is gated by `android.permission.DUMP` (the shell holds DUMP, normal third-party apps
 *   cannot get it), so only `adb shell content query` and privileged readers can query the row.
 * - [call] accepts `compile_package` and `optimize_now` requests and enforces
 *   `Binder.getCallingUid() == SHELL_UID` (`2000`) as its first check before reading arguments or
 *   touching state, rejecting non-shell callers (including apps and root) with [SecurityException].
 * - [insert], [delete], and [update] always throw [UnsupportedOperationException] so the status
 *   row can never be forged through ContentValues writes.
 *
 * The row normally comes from the in-process [CompileStatus] (which restored the
 * persisted in-progress compile as interrupted at startup). Before the app container
 * exists it falls back to the persisted record directly: a stored in-progress compile
 * then reads as `interrupted`, never `compiling`.
 */
class CompileStatusProvider : ContentProvider() {
    internal interface CallTarget {
        val ownPackage: String
        fun isInstalled(pkg: String): Boolean
        fun currentReqSeq(): Long
        suspend fun requestCompile(pkg: String, keepOptimized: Boolean): Boolean
        suspend fun requestOptimizeNow(): Int
        fun startWorker()
    }

    /** Latest snapshot; an idle row until the background collector's first pass. Never blocks. */
    @Volatile private var cached: List<Pair<String, String>> = idleRows()

    /** Serializes [refreshOnce] writers; the req_seq stamp decides which snapshot wins. */
    private val refreshMutex = Mutex()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile private var collectorStarted = false

    /** Test seam: replaces the live/persisted row source read by [refreshOnce]. */
    internal var refreshSource: (suspend () -> List<Pair<String, String>>?)? = null

    /** Test seam: replaces [Binder.getCallingUid] for JVM unit tests. */
    internal var callingUid: () -> Int = { Binder.getCallingUid() }

    /** Test seam: replaces the whole call handler inside [dispatchCall]. */
    internal var callHandler: (suspend (String, String?, Boolean) -> RemoteCallResult)? = null

    /** Test seam: replaces the production [SideSpeedApp.container] target used by [defaultCall]. */
    internal var callTarget: (() -> CallTarget?)? = null

    /** Test seam: overrides the 5 s [dispatchCall] timeout. */
    internal var callTimeoutMs: Long = CALL_TIMEOUT_MS

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

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        if (callingUid() != SHELL_UID) throw SecurityException("Shell only")
        val keep = extras?.getBoolean(EXTRA_KEEP, false) ?: false
        return dispatchCall(method, arg, keep).toBundle()
    }

    internal fun dispatchCall(method: String, arg: String?, keep: Boolean): RemoteCallResult {
        if (callingUid() != SHELL_UID) throw SecurityException("Shell only")
        return try {
            runBlocking(Dispatchers.Default) {
                withTimeout(callTimeoutMs) {
                    val handler = callHandler ?: { m, a, k -> defaultCall(m, a, k) }
                    val result = handler(method, arg, keep)
                    if (result.ok) refreshOnce()
                    result
                }
            }
        } catch (_: TimeoutCancellationException) {
            RemoteCallResult(ok = false, error = "timeout")
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            RemoteCallResult(ok = false, error = "interrupted")
        } catch (_: Exception) {
            RemoteCallResult(ok = false, error = "internal_error")
        }
    }

    private suspend fun defaultCall(method: String, arg: String?, keep: Boolean): RemoteCallResult = when (method) {
        METHOD_COMPILE_PACKAGE -> {
            val pkg = PackageName.parse(arg ?: "")?.value
                ?: return RemoteCallResult(ok = false, error = "invalid_package")
            val target = callTarget?.invoke() ?: appTarget()
                ?: return RemoteCallResult(ok = false, error = "not_ready")
            if (pkg == target.ownPackage) {
                RemoteCallResult(ok = true, queued = 0, reqSeq = target.currentReqSeq())
            } else if (!target.isInstalled(pkg)) {
                RemoteCallResult(ok = false, error = "not_installed")
            } else {
                val queued = if (target.requestCompile(pkg, keep)) {
                    target.startWorker()
                    1
                } else {
                    0
                }
                RemoteCallResult(ok = true, queued = queued, reqSeq = target.currentReqSeq())
            }
        }
        METHOD_OPTIMIZE_NOW -> {
            val target = callTarget?.invoke() ?: appTarget()
                ?: return RemoteCallResult(ok = false, error = "not_ready")
            val queued = target.requestOptimizeNow()
            if (queued > 0) target.startWorker()
            RemoteCallResult(ok = true, queued = queued, reqSeq = target.currentReqSeq())
        }
        else -> RemoteCallResult(ok = false, error = "unknown_method")
    }

    private suspend fun appTarget(): CallTarget? {
        val app = context?.applicationContext as? SideSpeedApp ?: return null
        val container = awaitContainer(app) ?: return null
        return object : CallTarget {
            override val ownPackage: String get() = app.packageName
            override fun isInstalled(pkg: String): Boolean = container.installedApps.find(pkg) != null
            override fun currentReqSeq(): Long = container.compileStatus.requestSeq()
            override suspend fun requestCompile(pkg: String, keepOptimized: Boolean): Boolean =
                container.maintenance.requestCompile(pkg, keepOptimized)
            override suspend fun requestOptimizeNow(): Int = container.maintenance.requestOptimizeNow()
            override fun startWorker() {
                MaintenanceService.start(app)
                runCatching { RemoteCompileJob.schedule(app) }
            }
        }
    }

    /** Cold `content call` binder IPC can arrive before `Application.onCreate()` sets `container`. */
    private suspend fun awaitContainer(app: SideSpeedApp): AppContainer? {
        repeat(CONTAINER_WAIT_ATTEMPTS) {
            runCatching { app.container }.getOrNull()?.let { return it }
            delay(CONTAINER_WAIT_STEP_MS)
        }
        return runCatching { app.container }.getOrNull()
    }

    /** The snapshot [query] serves: the latest collector pass, or the idle row before the first. */
    internal fun currentRows(): List<Pair<String, String>> = cached

    /** One collector pass; keeps the previous snapshot when the source fails or has no row. */
    internal suspend fun refreshOnce() {
        val rows = try {
            refreshSource?.invoke() ?: liveRow() ?: context?.persistedRow()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        if (rows != null) {
            refreshMutex.withLock {
                // A tick that read before a call's req_seq bump must not overwrite the newer row.
                if (reqSeqOf(rows) >= reqSeqOf(cached)) cached = rows
            }
        }
    }

    private fun reqSeqOf(rows: List<Pair<String, String>>): Long =
        rows.firstOrNull { it.first == KEY_REQ_SEQ }?.second?.toLongOrNull()?.takeIf { it >= 0L } ?: 0L

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

    private fun RemoteCallResult.toBundle(): Bundle = Bundle().apply {
        putBoolean(KEY_OK, ok)
        putInt(KEY_QUEUED, queued)
        putLong(KEY_REQ_SEQ, reqSeq)
        if (error != null) putString(KEY_ERROR, error)
    }

    companion object {
        /** Collector cadence: fresh enough for companion polls, cheap enough to run always. */
        const val STATUS_SNAPSHOT_REFRESH_MS = 2_000L

        const val SHELL_UID = 2000
        const val METHOD_COMPILE_PACKAGE = "compile_package"
        const val METHOD_OPTIMIZE_NOW = "optimize_now"
        const val EXTRA_KEEP = "keep"

        private const val KEY_OK = "ok"
        private const val KEY_QUEUED = "queued"
        private const val KEY_REQ_SEQ = "req_seq"
        private const val KEY_ERROR = "error"
        private const val CALL_TIMEOUT_MS = 5_000L
        private const val CONTAINER_WAIT_STEP_MS = 10L
        private const val CONTAINER_WAIT_ATTEMPTS = 200

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
