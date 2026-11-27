package io.github.devjardins.sidespeed.maintenance

import io.github.devjardins.sidespeed.apps.AppEntry
import io.github.devjardins.sidespeed.apps.AppSource
import io.github.devjardins.sidespeed.compile.CompileEvent
import io.github.devjardins.sidespeed.compile.CompileMode
import io.github.devjardins.sidespeed.compile.CompileOutcome
import io.github.devjardins.sidespeed.compile.PackageName
import io.github.devjardins.sidespeed.data.LastCompile
import io.github.devjardins.sidespeed.data.Prefs
import io.github.devjardins.sidespeed.helper.PackageNames
import io.github.devjardins.sidespeed.status.CompileStatus
import io.github.devjardins.sidespeed.status.StatusBadge
import io.github.devjardins.sidespeed.ui.text.StringLookup
import io.github.devjardins.sidespeed.ui.text.resolve
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
    private val strings: StringLookup,
    /** Package with an in-flight launch measurement; never compiled or queued while set. */
    private val measuring: () -> String? = { null },
    /** Fires once per helper down→up transition so a pending restore can retry. */
    private val onHelperUp: () -> Unit = {},
    private val status: CompileStatus? = null,
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
    private val priorityOneShot = LinkedHashSet<String>()
    private val oneShotTargets = LinkedHashSet<String>()
    private val reRequested = HashSet<String>()
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

    /** Suspends until no maintenance compile is running for [pkg]. */
    suspend fun awaitNotRunning(pkg: String) {
        state.first { it.phases[pkg] != JobPhase.RUNNING }
    }

    /** Manual retry: forgets earlier failures of [pkg] and enqueues it if stale. */
    fun retry(pkg: String) {
        synchronized(lock) {
            failures.remove(pkg)
            done.remove(pkg)
        }
        scan()
    }

    /**
     * Requests a priority one-shot compile for [pkg] (typically right after a remote install).
     * When [keepOptimized] is true, also adds [pkg] to the maintained set.
     * Never compiles [ownPackage], uninstalled packages, or a package currently under launch measurement.
     */
    suspend fun requestCompile(pkg: String, keepOptimized: Boolean): Boolean {
        if (pkg == ownPackage || !PackageNames.isValid(pkg)) return false
        if (source.find(pkg) == null) return false
        if (pkg == measuring()) {
            if (keepOptimized) store.setMaintained(pkg, true)
            return false
        }
        synchronized(lock) {
            failures.remove(pkg)
            done.remove(pkg)
            priorityOneShot.add(pkg)
            if (running == pkg) reRequested.add(pkg)
        }
        enqueue(pkg)
        if (keepOptimized) store.setMaintained(pkg, true)
        status?.bumpRequestSeq()
        publish()
        return true
    }

    /**
     * Clears retry backoffs and triggers an immediate optimization pass.
     * When armed, compiles stale maintained/covered apps; when unarmed (`SELECTED` with no ticked apps),
     * runs a one-shot pass over stale sideloaded apps without modifying stored mode or ticked apps.
     */
    suspend fun requestOptimizeNow(): Int = scanLock.withLock {
        synchronized(lock) { failures.clear() }
        val ticked = store.maintained.first()
        val mode = store.mode.first()
        var newlyEnqueued = 0
        if (!isArmed(mode, ticked)) {
            val skip = measuring()
            val sideloaded = source.list().filter {
                it.isSideloaded &&
                    it.packageName != ownPackage &&
                    it.packageName != skip &&
                    PackageNames.isValid(it.packageName)
            }
            val badgeByPkg = fetchBadges(sideloaded.map { it.packageName }.toSet())
            for (app in sideloaded) {
                val last = prefs.lastCompile(app.packageName).first()
                if (needsCompile(app, last, badgeByPkg[app.packageName]) && eligible(app)) {
                    synchronized(lock) { oneShotTargets.add(app.packageName) }
                    if (enqueue(app.packageName)) newlyEnqueued++
                }
            }
        }
        val scanEnqueued = scanOnce()
        status?.bumpRequestSeq()
        maxOf(newlyEnqueued + scanEnqueued, _state.value.queueLength)
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

    private suspend fun scanOnce(): Int {
        val ticked = store.maintained.first()
        val mode = store.mode.first()
        val tickedApps = ticked.mapNotNull { p -> source.find(p) ?: run { store.setMaintained(p, false); null } }
        val covered = when (mode) {
            AutoOptimizeMode.SELECTED -> emptyList()
            AutoOptimizeMode.SIDELOADED -> source.list().filter { it.isSideloaded }
            AutoOptimizeMode.ALL -> source.list().filter { !it.isSystem }
        }
        val skip = measuring()
        val set: List<AppEntry> = (tickedApps + covered)
            .filter { it.packageName != ownPackage && it.packageName != skip && PackageNames.isValid(it.packageName) }
            .distinctBy { it.packageName }
        val names = set.map { it.packageName }.toSet()
        val extraNames = synchronized(lock) {
            if (skip != null) {
                priorityOneShot.remove(skip)
                oneShotTargets.remove(skip)
                reRequested.remove(skip)
            }
            priorityOneShot.remove(ownPackage)
            oneShotTargets.remove(ownPackage)
            reRequested.remove(ownPackage)
            (priorityOneShot + oneShotTargets) - names
        }
        val extraApps = extraNames.mapNotNull { p ->
            source.find(p) ?: run {
                synchronized(lock) {
                    priorityOneShot.remove(p)
                    oneShotTargets.remove(p)
                    reRequested.remove(p)
                }
                null
            }
        }
        synchronized(lock) {
            maintainedNow = names
            val retained = names + priorityOneShot + oneShotTargets
            queue.retainAll(retained)
            failures.keys.retainAll(retained)
        }
        val badgeByPkg = fetchBadges(names)
        var enqueued = 0
        for (app in set) {
            val last = prefs.lastCompile(app.packageName).first()
            if (needsCompile(app, last, badgeByPkg[app.packageName]) && eligible(app)) {
                if (enqueue(app.packageName)) enqueued++
            }
        }
        for (app in extraApps) {
            if (eligible(app) && enqueue(app.packageName)) enqueued++
        }
        publish()
        return enqueued
    }

    private suspend fun fetchBadges(names: Set<String>): Map<String, StatusBadge> =
        if (names.isEmpty()) emptyMap() else try {
            badges(names)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyMap()
        }

    private fun eligible(app: AppEntry): Boolean = synchronized(lock) {
        val f = failures[app.packageName]
        if (f != null && f.updateTime != app.lastUpdateTime) failures.remove(app.packageName)
        val failed = failures[app.packageName]
        if (failed != null && !failed.due) return false
        done[app.packageName] != app.lastUpdateTime
    }

    private fun enqueue(pkg: String): Boolean {
        val added = synchronized(lock) { pkg != running && queue.add(pkg) }
        if (added) wake.trySend(Unit)
        return added
    }

    private suspend fun drain() {
        while (true) {
            val pkg = synchronized(lock) {
                priorityOneShot.firstOrNull { it in queue } ?: queue.firstOrNull()
            } ?: return
            // A launch sequence owns this package right now: drop it (its own
            // recompile is the maintenance), a later scan re-adds it if stale.
            if (pkg == measuring()) {
                synchronized(lock) {
                    queue.remove(pkg)
                    priorityOneShot.remove(pkg)
                    oneShotTargets.remove(pkg)
                    reRequested.remove(pkg)
                }
                publish()
                continue
            }
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
                val requeue = synchronized(lock) {
                    running = null
                    reRequested.remove(pkg)
                }
                if (requeue) enqueue(pkg)
                publish()
            }
            doScan()
        }
    }

    private suspend fun runJob(pkg: String) {
        val app = source.find(pkg) ?: run {
            store.setMaintained(pkg, false)
            dropOneShot(pkg, force = true)
            return
        }
        val name = PackageName.parse(pkg) ?: run {
            dropOneShot(pkg, force = true)
            return
        }
        val mode = prefs.lastCompile(pkg).first()?.mode ?: CompileMode.SPEED
        // A launch sequence may have started after this job was taken: never compile under it.
        if (pkg == measuring()) {
            dropOneShot(pkg, force = true)
            return
        }
        val start = clock()
        status?.onStart(pkg, app.label, mode.arg)
        var finished: CompileEvent.Finished? = null
        try {
            runCompile(name, mode).collect { if (it is CompileEvent.Finished) finished = it }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val detail = e.message?.takeIf { it.isNotEmpty() }
            finished = CompileEvent.Finished(false, false, CompileOutcome.Error(detail))
        }
        val result = finished ?: CompileEvent.Finished(false, false, CompileOutcome.Failure)
        when {
            result.success -> {
                status?.onFinish(pkg, app.label, true)
                // The start time, not the end: an update that lands mid-compile stays newer and gets compiled again.
                prefs.setLastCompile(pkg, LastCompile(start, mode))
                synchronized(lock) {
                    done[pkg] = app.lastUpdateTime
                    failures.remove(pkg)
                }
                dropOneShot(pkg, force = false)
                val message = JobMessage.SUCCESS.text(null).resolve(strings, rtl = false)
                store.addHistory(JobRecord(pkg, app.label, clock(), true, message, JobMessage.SUCCESS.name))
            }
            !helperUp() -> {
                status?.onHelperLost()
                synchronized(lock) { queue.add(pkg) }
            }
            else -> {
                status?.onFinish(pkg, app.label, false)
                val attempts = synchronized(lock) {
                    ((failures[pkg]?.attempts ?: 0) + 1).also { failures[pkg] = Failure(app.lastUpdateTime, it, false) }
                }
                val (jobMessage, detail) = classifyJobFailure(result.outcome)
                val message = jobMessage.text(detail).resolve(strings, rtl = false)
                store.addHistory(JobRecord(pkg, app.label, clock(), false, message, jobMessage.name, detail))
                if (attempts <= maxRetries) {
                    scope.launch {
                        delay(retryDelayMs)
                        synchronized(lock) { failures[pkg]?.let { failures[pkg] = Failure(it.updateTime, it.attempts, true) } }
                        scan()
                    }
                } else {
                    dropOneShot(pkg, force = false)
                }
            }
        }
    }

    private fun dropOneShot(pkg: String, force: Boolean) = synchronized(lock) {
        if (force) reRequested.remove(pkg)
        if (force || pkg !in reRequested) {
            priorityOneShot.remove(pkg)
            oneShotTargets.remove(pkg)
        }
    }

    private fun classifyJobFailure(outcome: CompileOutcome): Pair<JobMessage, String?> = when (outcome) {
        CompileOutcome.Success, CompileOutcome.Failure -> JobMessage.FAILURE to null
        CompileOutcome.ConnectionLost -> JobMessage.CONNECTION_LOST to null
        is CompileOutcome.Error -> JobMessage.ERROR to outcome.detail?.takeIf { it.isNotEmpty() }
    }

    private fun setHelperDown(down: Boolean) {
        val wasDown = synchronized(lock) {
            val was = helperDown
            helperDown = down
            was
        }
        // The drain loop calls with false on every pass while up; fire only on the transition.
        if (wasDown && !down) runCatching { onHelperUp() }
        status?.setHelperDown(down)
        publish()
    }

    private fun publish() {
        val (queueSize, nextState) = synchronized(lock) {
            val phases = LinkedHashMap<String, JobPhase>()
            queue.forEach { phases[it] = JobPhase.WAITING }
            running?.let { phases[it] = JobPhase.RUNNING }
            failures.keys.forEach { if (it !in phases) phases[it] = JobPhase.RETRY }
            queue.size to MaintenanceState(maintainedNow, phases, helperDown)
        }
        status?.setQueueWaiting(queueSize)
        status?.setRetrying(nextState.phases.values.count { it == JobPhase.RETRY })
        _state.value = nextState
    }
}
