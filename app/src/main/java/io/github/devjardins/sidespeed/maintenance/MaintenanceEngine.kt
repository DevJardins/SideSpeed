package io.github.devjardins.sidespeed.maintenance

import io.github.devjardins.sidespeed.apps.AppEntry
import io.github.devjardins.sidespeed.apps.AppSource
import io.github.devjardins.sidespeed.compile.CompileEvent
import io.github.devjardins.sidespeed.compile.CompileMode
import io.github.devjardins.sidespeed.compile.PackageName
import io.github.devjardins.sidespeed.data.LastCompile
import io.github.devjardins.sidespeed.data.Prefs
import io.github.devjardins.sidespeed.status.StatusBadge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.cancellation.CancellationException

enum class JobPhase { WAITING, RUNNING, RETRY }

data class MaintenanceState(
    val maintained: Set<String> = emptySet(),
    /** Only apps with work queued, running, or a failure awaiting retry. */
    val phases: Map<String, JobPhase> = emptyMap(),
    val helperDown: Boolean = false,
) {
    val queueLength: Int get() = phases.values.count { it == JobPhase.WAITING || it == JobPhase.RUNNING }
}

/**
 * Keeps maintained apps compiled. One serial queue, one worker; [scan] is idempotent and safe to call from any trigger.
 * The queue has no storage: it is rebuilt from staleness (see [isStale]) after a restart.
 */
class MaintenanceEngine(
    private val source: AppSource,
    private val store: MaintenanceStore,
    private val prefs: Prefs,
    private val runCompile: (PackageName, CompileMode) -> Flow<CompileEvent>,
    private val helperUp: suspend () -> Boolean,
    /** Current compiler badge per package from one status call; may throw, then staleness falls back to time alone. */
    private val badges: suspend (Collection<String>) -> Map<String, StatusBadge> = { emptyMap() },
    private val scope: CoroutineScope,
    private val ownPackage: String,
    private val clock: () -> Long = System::currentTimeMillis,
    private val retryDelayMs: Long = 30 * 60_000L,
    private val maxRetries: Int = 3,
    private val helperPollMs: Long = 30_000L,
) {
    private class Failure(val updateTime: Long, val attempts: Int, val due: Boolean)

    private val _state = MutableStateFlow(MaintenanceState())
    val state: StateFlow<MaintenanceState> = _state.asStateFlow()

    private val lock = Any()
    private val queue = LinkedHashSet<String>()
    private var running: String? = null
    private var maintainedNow: Set<String> = emptySet()
    private var helperDown = false
    private val failures = HashMap<String, Failure>()

    /** `lastUpdateTime` each app had when it last compiled successfully; stops a skewed clock looping a recompile. */
    private val done = HashMap<String, Long>()
    private val scanLock = Mutex()
    private val wake = Channel<Unit>(Channel.CONFLATED)

    init {
        scope.launch {
            combine(store.maintained.distinctUntilChanged(), store.mode.distinctUntilChanged()) { _, _ -> }
                .catch { }
                .collect { doScan() }
        }
        scope.launch { for (ignored in wake) drain() }
    }

    /** Finds stale maintained apps and enqueues them; prunes ticked apps that are no longer installed. */
    fun scan() {
        scope.launch { doScan() }
    }

    /** Manual retry: forgets earlier failures of [pkg] and enqueues it if stale. */
    fun retry(pkg: String) {
        synchronized(lock) {
            failures.remove(pkg)
            done.remove(pkg)
        }
        scan()
    }

    /** Storage or PackageManager errors must never kill the process; the next trigger retries. */
    private suspend fun doScan() = scanLock.withLock {
        try {
            scanOnce()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    private suspend fun scanOnce() {
        val ticked = store.maintained.first()
        val mode = store.mode.first()
        val tickedApps = ticked.mapNotNull { p -> source.find(p) ?: run { store.setMaintained(p, false); null } }
        val covered = when (mode) {
            AutoOptimizeMode.SELECTED -> emptyList()
            AutoOptimizeMode.SIDELOADED -> source.list().filter { it.isSideloaded }
            AutoOptimizeMode.ALL -> source.list().filter { !it.isSystem }
        }
        val set: List<AppEntry> = (tickedApps + covered).filter { it.packageName != ownPackage }.distinctBy { it.packageName }
        val names = set.map { it.packageName }.toSet()
        synchronized(lock) {
            maintainedNow = names
            queue.retainAll(names)
            failures.keys.retainAll(names)
        }
        val badgeByPkg = if (set.isEmpty()) emptyMap() else try {
            badges(names)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyMap()
        }
        for (app in set) {
            val last = prefs.lastCompile(app.packageName).first()
            if (needsCompile(app, last, badgeByPkg[app.packageName]) && eligible(app)) enqueue(app.packageName)
        }
        publish()
    }

    private fun eligible(app: AppEntry): Boolean = synchronized(lock) {
        val f = failures[app.packageName]
        if (f != null && f.updateTime != app.lastUpdateTime) failures.remove(app.packageName)
        val failed = failures[app.packageName]
        if (failed != null && !failed.due) return false
        done[app.packageName] != app.lastUpdateTime
    }

    private fun enqueue(pkg: String) {
        val added = synchronized(lock) { pkg != running && queue.add(pkg) }
        if (added) wake.trySend(Unit)
    }

    private suspend fun drain() {
        while (true) {
            val pkg = synchronized(lock) { queue.firstOrNull() } ?: return
            if (!helperUp()) {
                setHelperDown(true)
                delay(helperPollMs)
                continue
            }
            setHelperDown(false)
            val taken = synchronized(lock) { (queue.remove(pkg)).also { if (it) running = pkg } }
            if (!taken) continue
            publish()
            try {
                runJob(pkg)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            } finally {
                synchronized(lock) { running = null }
                publish()
            }
            doScan()
        }
    }

    private suspend fun runJob(pkg: String) {
        val app = source.find(pkg) ?: run { store.setMaintained(pkg, false); return }
        val name = PackageName.parse(pkg) ?: return
        val mode = prefs.lastCompile(pkg).first()?.mode ?: CompileMode.SPEED
        val start = clock()
        var finished: CompileEvent.Finished? = null
        try {
            runCompile(name, mode).collect { if (it is CompileEvent.Finished) finished = it }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            finished = CompileEvent.Finished(false, false, e.message ?: "Error")
        }
        val result = finished ?: CompileEvent.Finished(false, false, "Failure")
        when {
            result.success -> {
                // The start time, not the end: an update that lands mid-compile stays newer and gets compiled again.
                prefs.setLastCompile(pkg, LastCompile(start, mode))
                synchronized(lock) {
                    done[pkg] = app.lastUpdateTime
                    failures.remove(pkg)
                }
                store.addHistory(JobRecord(pkg, app.label, clock(), true, result.message))
            }
            !helperUp() -> synchronized(lock) { queue.add(pkg) }
            else -> {
                val attempts = synchronized(lock) {
                    ((failures[pkg]?.attempts ?: 0) + 1).also { failures[pkg] = Failure(app.lastUpdateTime, it, false) }
                }
                store.addHistory(JobRecord(pkg, app.label, clock(), false, result.message))
                if (attempts <= maxRetries) {
                    scope.launch {
                        delay(retryDelayMs)
                        synchronized(lock) { failures[pkg]?.let { failures[pkg] = Failure(it.updateTime, it.attempts, true) } }
                        scan()
                    }
                }
            }
        }
    }

    private fun setHelperDown(down: Boolean) {
        synchronized(lock) { helperDown = down }
        publish()
    }

    private fun publish() {
        _state.value = synchronized(lock) {
            val phases = LinkedHashMap<String, JobPhase>()
            queue.forEach { phases[it] = JobPhase.WAITING }
            running?.let { phases[it] = JobPhase.RUNNING }
            failures.keys.forEach { if (it !in phases) phases[it] = JobPhase.RETRY }
            MaintenanceState(maintainedNow, phases, helperDown)
        }
    }
}
