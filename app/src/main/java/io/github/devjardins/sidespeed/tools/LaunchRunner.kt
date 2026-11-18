package io.github.devjardins.sidespeed.tools

import io.github.devjardins.sidespeed.apps.PkgVersion
import io.github.devjardins.sidespeed.compile.CompileEvent
import io.github.devjardins.sidespeed.compile.CompileMode
import io.github.devjardins.sidespeed.compile.CompileRunner
import io.github.devjardins.sidespeed.compile.PackageName
import io.github.devjardins.sidespeed.data.LastCompile
import io.github.devjardins.sidespeed.data.Prefs
import io.github.devjardins.sidespeed.status.CompileStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Runs the device compile with screen updates; provided by the Compile screen. */
typealias UiCompile = suspend (PackageName, CompileMode) -> CompileEvent.Finished

/** Per-package launch state the Compile screen observes: in-flight busy label or sticky error. */
data class LaunchProgress(val busy: String? = null, val error: String? = null)

/** What the Compile screen shows for a package: resolution, stored result, whether a before run is needed. */
data class LaunchPkg(
    val launchable: Boolean,
    val cls: String?,
    val version: PkgVersion?,
    val stored: LaunchResult?,
    val stale: Boolean,
    val needsBefore: Boolean,
)

private const val RESTORE_ERROR = "The app was left reset; SideSpeed will recompile it."

/**
 * Owns launch-timing sequences in a container-level scope, so Back, screen or
 * ViewModel teardown, and cancellation never strand an app in `verify`:
 * after `resetCompiled` a [RestorePending] record is persisted and the post-reset
 * restore runs in `try/finally` under [NonCancellable]. The record is cleared
 * only after a successful recompile; a [restorePending] retry runs on app
 * start, the maintenance loop, and helper-up. Single-flight: one sequence at a
 * time; extra starts return null. Never runs from background maintenance.
 */
class LaunchRunner(
    private val commands: ToolsCommands,
    private val store: ToolsStore,
    private val prefs: Prefs,
    private val compile: CompileRunner,
    private val scope: CoroutineScope,
    private val awaitEngineIdle: suspend (String) -> Unit,
    /** Suspends until no self-compile is running; awaited before every before-timing. */
    private val awaitSelfIdle: suspend () -> Unit = {},
    private val launchActivityOf: (String) -> String? = { null },
    private val versionOf: (String) -> PkgVersion? = { null },
    private val keepAlive: () -> Unit = {},
    private val clock: () -> Long = System::currentTimeMillis,
    private val runs: Int = 3,
    /** Live compile status; the timed/measure recompiles report through the caller's UiCompile. */
    private val status: CompileStatus? = null,
    private val helperUp: suspend () -> Boolean = { true },
    private val labelOf: (String) -> String? = { null },
) {
    private val _measuring = MutableStateFlow<String?>(null)

    /** Package with an in-flight launch sequence, or null. Drives the service hold and the engine skip. */
    val measuring: StateFlow<String?> = _measuring.asStateFlow()

    private val _progress = MutableStateFlow<Map<String, LaunchProgress>>(emptyMap())
    val progress: StateFlow<Map<String, LaunchProgress>> = _progress.asStateFlow()

    fun isBusy(): Boolean = _measuring.value != null

    fun progressFor(pkg: String): Flow<LaunchProgress?> = progress.map { it[pkg] }

    /** Stored launch results for one package; the screen observes it so a finished sequence refreshes. */
    fun storedResults(pkg: String): Flow<LaunchResult?> = store.launchResult(pkg)

    /** Resolution + stored result for the Compile screen; failures degrade to the disabled section. */
    suspend fun describe(pkg: String): LaunchPkg = try {
        val stored = store.launchResult(pkg).first()
        val cls = launchActivityOf(pkg)
        val ver = versionOf(pkg)
        val needs = if (ver == null) stored == null else needsBeforeMeasure(stored, ver.versionCode, ver.lastUpdateTime)
        val stale = stored != null && ver != null &&
            (stored.versionCode != ver.versionCode || stored.lastUpdateTime != ver.lastUpdateTime)
        LaunchPkg(cls != null, cls, ver, stored, stale, needs)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        LaunchPkg(launchable = false, cls = null, version = null, stored = null, stale = false, needsBefore = false)
    }

    fun clearError(pkg: String) {
        _progress.value = _progress.value.let { cur -> if (cur[pkg]?.busy == null) cur - pkg else cur }
    }

    /**
     * Option A: time before, compile, time after, store. The checkbox gating
     * lives with the caller; null means another sequence is running.
     */
    fun startTimed(pkg: PackageName, mode: CompileMode, uiCompile: UiCompile): Job? {
        if (!_measuring.compareAndSet(null, pkg.value)) return null
        setBusy(pkg.value, "Timing launch before…")
        keepAlive()
        return scope.launch {
            var failMsg: String? = null
            try {
                failMsg = runTimed(pkg, mode, uiCompile)
            } finally {
                withContext(NonCancellable) { finishSequence(pkg.value, failMsg) }
            }
        }
    }

    /**
     * Option B: reset to verify, time, recompile to the app's previous mode
     * (speed when unknown), time again, store. Null when busy. On any failure
     * after the reset the app is recompiled first (or left pending for retry).
     */
    /**
     * In-memory restore state for the in-flight sequence, so the finally below
     * still recompiles when the store record is missing (failed write or read).
     * Only the sequence holding [_measuring] touches these; [finishSequence]
     * always consumes them. Survives coroutine cancellation, unlike locals.
     */
    private var inFlightMemMode: CompileMode? = null
    private var inFlightRecompiled = false

    fun measure(pkg: PackageName, uiCompile: UiCompile): Job? {
        if (!_measuring.compareAndSet(null, pkg.value)) return null
        setBusy(pkg.value, "Resetting…")
        keepAlive()
        inFlightMemMode = null
        inFlightRecompiled = false
        return scope.launch {
            var failMsg: String? = null
            try {
                failMsg = runMeasure(pkg, uiCompile)
            } finally {
                withContext(NonCancellable) { finishSequence(pkg.value, failMsg) }
            }
        }
    }

    /**
     * Recompiles a pending record, if any. No-op when a sequence is running
     * (its finally owns the restore) and when there is nothing pending.
     * Never throws except on scope cancellation.
     */
    suspend fun restorePending() {
        try {
            val pending = store.restorePending.first() ?: return
            if (!_measuring.compareAndSet(null, pending.pkg)) return
            try {
                if (restoreRecord(pending)) setProgress(pending.pkg, null)
                else setProgress(pending.pkg, LaunchProgress(error = RESTORE_ERROR))
            } finally {
                _measuring.compareAndSet(pending.pkg, null)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    private suspend fun runTimed(pkg: PackageName, mode: CompileMode, uiCompile: UiCompile): String? {
        try {
            val d = describe(pkg.value)
            val cls = d.cls ?: return "This app can't be opened from SideSpeed"
            val ver = d.version ?: return "Couldn't read this app's version"
            val timer = LaunchTimer(commands, runs)
            // A self-compile started on an earlier leave must finish first:
            // it compiles SideSpeed itself and would skew the before number.
            setBusy(pkg.value, "Waiting for SideSpeed to finish optimizing itself…")
            try {
                awaitSelfIdle()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                return "Couldn't start the measurement"
            }
            setBusy(pkg.value, "Timing launch before…")
            val before = when (val r = timer.medianMs(pkg.value, cls)) {
                is ToolResult.Ok -> r.value
                else -> return timingMessage(r)
            }
            setBusy(pkg.value, "Compiling…")
            val result = uiCompile(pkg, mode)
            // The screen shows the compile failure; no reset ran, so nothing to restore.
            if (!result.success) return null
            setBusy(pkg.value, "Timing launch after…")
            val after = when (val r = timer.medianMs(pkg.value, cls)) {
                is ToolResult.Ok -> r.value
                else -> return timingMessage(r)
            }
            return try {
                store.setLaunchResult(LaunchResult(pkg.value, ver.versionCode, ver.lastUpdateTime, before, after, clock()))
                null
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                "Couldn't save the result"
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return e.message ?: "Couldn't measure this app"
        }
    }

    /**
     * Returns the failure message, or null when the result was stored. The outer
     * finally restores from the in-memory mode ([inFlightMemMode]) and the store
     * record; [inFlightRecompiled] marks a landed recompile so a leftover record
     * from a failed clear is left for the restorer instead of recompiling again.
     */
    private suspend fun runMeasure(pkg: PackageName, uiCompile: UiCompile): String? {
        try {
            val d = describe(pkg.value)
            val cls = d.cls ?: return "This app can't be opened from SideSpeed"
            val ver = d.version ?: return "Couldn't read this app's version"
            val mode = previousMode(pkg.value)
            val timer = LaunchTimer(commands, runs)
            // Wait out any in-flight maintenance compile first: the engine skips
            // newly measured packages, but one already running must finish.
            // A self-compile started on an earlier leave must finish too: it
            // compiles SideSpeed itself and would skew the before number.
            setBusy(pkg.value, "Waiting for SideSpeed to finish optimizing this app…")
            try {
                awaitEngineIdle(pkg.value)
                awaitSelfIdle()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                return "Couldn't start the measurement"
            }
            // Write-ahead: persist BEFORE reset so a crash/cancel in the gap still
            // restores. Best effort: if the write itself fails, the in-memory mode
            // below still restores after reset.
            try {
                store.setRestorePending(RestorePending(pkg.value, mode, ver.versionCode))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
            // Set before the reset: a HelperDown (e.g. mid-command AdbConnectionLost)
            // may mean the reset already ran on the device, so the finally must
            // restore. Never clear here; recompiling a never-reset app is harmless.
            inFlightMemMode = mode
            setBusy(pkg.value, "Resetting…")
            when (val reset = commands.resetCompiled(pkg.value)) {
                is ToolResult.Ok -> {}
                else -> return timingMessage(reset)
            }
            setBusy(pkg.value, "Timing launch…")
            val before = when (val r = timer.medianMs(pkg.value, cls)) {
                is ToolResult.Ok -> r.value
                else -> return timingMessage(r)
            }
            setBusy(pkg.value, "Compiling…")
            val result = uiCompile(pkg, mode)
            // The screen shows the compile failure; the outer finally restores.
            if (!result.success) return null
            // Successful recompile: the app is no longer pending. A record left by
            // a failed clear stays for the restorer; inFlightRecompiled suppresses
            // a redundant immediate recompile.
            clearPendingQuietly()
            inFlightRecompiled = true
            setBusy(pkg.value, "Timing launch again…")
            val after = when (val r = timer.medianMs(pkg.value, cls)) {
                is ToolResult.Ok -> r.value
                else -> return timingMessage(r)
            }
            return try {
                store.setLaunchResult(LaunchResult(pkg.value, ver.versionCode, ver.lastUpdateTime, before, after, clock()))
                null
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                "Couldn't save the result"
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return e.message ?: "Couldn't measure this app"
        }
    }

    private suspend fun finishSequence(pkg: String, failMsg: String?) {
        val memMode = inFlightMemMode
        val recompiled = inFlightRecompiled
        inFlightMemMode = null
        inFlightRecompiled = false
        if (recompiled) {
            // The recompile landed; never restore here. A pending record means the
            // post-success clear failed — it stays for the restorer. Surface only
            // a save error, if any.
            setProgress(pkg, failMsg?.let { LaunchProgress(error = it) })
            _measuring.compareAndSet(pkg, null)
            return
        }
        val restored = restoreIfPending(pkg, memMode)
        val error = when {
            restored && failMsg == null -> null
            restored -> failMsg
            failMsg == null -> RESTORE_ERROR
            else -> "$failMsg $RESTORE_ERROR"
        }
        setProgress(pkg, error?.let { LaunchProgress(error = it) })
        _measuring.compareAndSet(pkg, null)
    }

    /**
     * Recompiles [pkg]'s pending record when it is still ours; true when nothing
     * is left to do. Falls back to [memMode] when the store record is missing
     * (failed write or unreadable store) after a successful reset.
     */
    private suspend fun restoreIfPending(pkg: String, memMode: CompileMode?): Boolean {
        val stored = try {
            store.restorePending.first()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        if (stored != null && stored.pkg != pkg) return true
        val mode = memMode ?: stored?.mode ?: return true
        val name = PackageName.parse(pkg) ?: run {
            clearPendingQuietly()
            return true
        }
        if (!quietCompile(name, mode)) return false
        clearPendingQuietly()
        return true
    }

    private suspend fun restoreRecord(pending: RestorePending): Boolean {
        val name = PackageName.parse(pending.pkg) ?: run {
            clearPendingQuietly()
            return true
        }
        if (!quietCompile(name, pending.mode)) return false
        clearPendingQuietly()
        return true
    }

    /**
     * Restore-path compile: the resetCompiled step never reaches here, so every
     * compile here is a real recompile and reports exactly one onStart/onFinish
     * pair, mirroring the maintenance engine (helper loss reports interrupted).
     */
    private suspend fun quietCompile(pkg: PackageName, mode: CompileMode): Boolean {
        val label = labelOf(pkg.value) ?: pkg.value
        status?.onStart(pkg.value, label, mode.arg)
        var ok = false
        try {
            compile.run(pkg, mode).collect { e -> if (e is CompileEvent.Finished) ok = e.success }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            ok = false
        }
        if (!ok) {
            if (!helperUp()) status?.onHelperLost() else status?.onFinish(pkg.value, label, false)
            return false
        }
        return try {
            prefs.setLastCompile(pkg.value, LastCompile(clock(), mode))
            status?.onFinish(pkg.value, label, true)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun previousMode(pkg: String): CompileMode = try {
        prefs.lastCompile(pkg).first()?.mode
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    } ?: CompileMode.SPEED

    private suspend fun clearPendingQuietly() {
        try {
            store.setRestorePending(null)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    private fun setProgress(pkg: String, p: LaunchProgress?) {
        _progress.value = if (p == null) _progress.value - pkg else _progress.value + (pkg to p)
    }

    private fun setBusy(pkg: String, label: String) = setProgress(pkg, LaunchProgress(busy = label))

    private fun timingMessage(r: ToolResult<*>): String = when (r) {
        is ToolResult.Ok -> "Couldn't time this app"
        is ToolResult.HelperDown -> "Helper not running"
        is ToolResult.HelperOutdated -> "Restart the helper to use this"
        is ToolResult.Failed -> "Couldn't time this app"
    }
}
