package io.github.devjardins.sidespeed.status

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The persisted half of the compile status: an in-progress compile and the last result. */
interface CompileStatusStore {
    suspend fun load(): PersistedCompileStatus?
    suspend fun save(value: PersistedCompileStatus?)
}

private val Context.compileStatusDataStore by preferencesDataStore("sidespeed_compile_status")

/**
 * Own DataStore file, so the existing `Prefs` interface and its test fakes stay untouched.
 * One key per field: labels are stored verbatim, so no escaping round trip can corrupt them.
 */
class DataStoreCompileStatusStore(private val store: DataStore<Preferences>) : CompileStatusStore {
    constructor(context: Context) : this(context.applicationContext.compileStatusDataStore)

    private val curPkg = stringPreferencesKey("cur_pkg")
    private val curLabel = stringPreferencesKey("cur_label")
    private val curMode = stringPreferencesKey("cur_mode")
    private val curStarted = longPreferencesKey("cur_started")
    private val lastPkg = stringPreferencesKey("last_pkg")
    private val lastLabel = stringPreferencesKey("last_label")
    private val lastOk = booleanPreferencesKey("last_ok")
    private val lastMs = longPreferencesKey("last_ms")

    override suspend fun load(): PersistedCompileStatus? {
        val p = store.data.first()
        val current = p[curPkg]?.let { pkg ->
            CompileCurrent(pkg, p[curLabel] ?: "", p[curMode] ?: "", p[curStarted] ?: 0L)
        }
        val last = p[lastPkg]?.let { pkg ->
            p[lastMs]?.let { ms -> CompileLast(pkg, p[lastLabel] ?: "", p[lastOk] ?: false, ms) }
        }
        return if (current == null && last == null) null else PersistedCompileStatus(current, last)
    }

    override suspend fun save(value: PersistedCompileStatus?) {
        store.edit { prefs ->
            if (value?.current != null) {
                val c = value.current
                prefs[curPkg] = c.pkg
                prefs[curLabel] = c.label
                prefs[curMode] = c.mode
                prefs[curStarted] = c.startedMs
            } else {
                prefs.remove(curPkg)
                prefs.remove(curLabel)
                prefs.remove(curMode)
                prefs.remove(curStarted)
            }
            if (value?.last != null) {
                val l = value.last
                prefs[lastPkg] = l.pkg
                prefs[lastLabel] = l.label
                prefs[lastOk] = l.ok
                prefs[lastMs] = l.finishedMs
            } else {
                prefs.remove(lastPkg)
                prefs.remove(lastLabel)
                prefs.remove(lastOk)
                prefs.remove(lastMs)
            }
        }
    }
}

/**
 * The app-wide compile-status reporter. Reporters call the synchronous setters; every
 * mutation is persisted, and a cold start restores the in-progress compile as interrupted.
 */
class CompileStatus(
    private val store: CompileStatusStore,
    version: String,
    debuggable: Boolean,
    private val loadSelfOptimized: suspend () -> Boolean = { false },
    clock: () -> Long = System::currentTimeMillis,
    private val scope: CoroutineScope,
) {
    private val tracker = CompileStatusTracker(version, debuggable, clock)
    private val mutex = Mutex()

    init {
        scope.launch {
            mutex.withLock {
                tracker.restore(store.load())
                tracker.setSelfOptimized(loadSelfOptimized())
            }
        }
    }

    fun onStart(pkg: String, label: String, mode: String) = mutate { onStart(pkg, label, mode) }
    fun onFinish(pkg: String, label: String, ok: Boolean) = mutate { onFinish(pkg, label, ok) }
    fun onHelperLost() = mutate { onHelperLost() }
    fun setHelperDown(down: Boolean) = mutate { setHelperDown(down) }
    fun setQueueWaiting(n: Int) = mutate { setQueueWaiting(n) }
    fun setSelfOptimized(optimized: Boolean) = mutate { setSelfOptimized(optimized) }

    suspend fun snapshot(): StatusRow = mutex.withLock { tracker.snapshot() }

    private fun mutate(block: CompileStatusTracker.() -> Unit) {
        scope.launch { mutex.withLock { tracker.block(); store.save(tracker.export()) } }
    }
}
