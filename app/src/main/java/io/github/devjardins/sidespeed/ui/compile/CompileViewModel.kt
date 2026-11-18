package io.github.devjardins.sidespeed.ui.compile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.devjardins.sidespeed.apps.PkgVersion
import io.github.devjardins.sidespeed.compile.CompileEvent
import io.github.devjardins.sidespeed.compile.CompileMode
import io.github.devjardins.sidespeed.compile.CompileRunner
import io.github.devjardins.sidespeed.compile.PackageName
import io.github.devjardins.sidespeed.data.LastCompile
import io.github.devjardins.sidespeed.data.Prefs
import io.github.devjardins.sidespeed.maintenance.MaintenanceStore
import io.github.devjardins.sidespeed.status.DexoptParser
import io.github.devjardins.sidespeed.status.StatusBadge
import io.github.devjardins.sidespeed.status.StatusChecker
import io.github.devjardins.sidespeed.tools.LaunchResult
import io.github.devjardins.sidespeed.tools.LaunchTimer
import io.github.devjardins.sidespeed.tools.ToolResult
import io.github.devjardins.sidespeed.tools.ToolsCommands
import io.github.devjardins.sidespeed.tools.ToolsStore
import io.github.devjardins.sidespeed.tools.needsBeforeMeasure
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

data class CompileUiState(
    val log: List<String> = emptyList(),
    val running: Boolean = false,
    val result: CompileEvent.Finished? = null,
    val badge: StatusBadge? = null,
    val startedAtMs: Long = 0L,
    val finishedAtMs: Long = 0L,
    /** A successful compile of an app that is not yet kept optimized: offer to tick it. */
    val offerKeepOptimized: Boolean = false,
    /** The app is ticked for automatic recompiling. */
    val kept: Boolean = false,
) {
    /** Whole seconds the finished compile took (0 before it finishes). */
    val durationSeconds: Long get() = if (finishedAtMs > 0) ((finishedAtMs - startedAtMs) / 1000).coerceAtLeast(0) else 0
}

const val MAX_LOG_LINES = 2000

/**
 * Launch-timing state for one package on the Compile screen. [launchable] is
 * false when the app has no launch activity SideSpeed can open; [stale] marks
 * a stored result from an older installed version.
 */
data class LaunchUiState(
    val launchable: Boolean = false,
    val stored: LaunchResult? = null,
    val stale: Boolean = false,
    val needsBefore: Boolean = false,
    val checked: Boolean = true,
    val busy: String? = null,
    val error: String? = null,
)

class CompileViewModel(
    private val runner: CompileRunner,
    private val checker: StatusChecker,
    private val prefs: Prefs,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val store: MaintenanceStore? = null,
    private val tools: ToolsCommands? = null,
    private val toolsStore: ToolsStore? = null,
    private val launchRuns: Int = 3,
    private val launchActivityOf: (String) -> String? = { null },
    private val versionOf: (String) -> PkgVersion? = { null },
    /** Keeps the process alive while the measured app is on screen (foreground service). */
    private val keepAlive: () -> Unit = {},
    private val clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {
    private val _state = MutableStateFlow(CompileUiState())
    val state: StateFlow<CompileUiState> = _state.asStateFlow()

    private val _launch = MutableStateFlow(LaunchUiState())
    val launch: StateFlow<LaunchUiState> = _launch.asStateFlow()

    /** Clears the previous run's output (called when a package is opened); ignored while a compile is running. */
    fun reset() {
        if (_state.value.running) return
        _state.value = CompileUiState()
    }

    /** Starts a compile; ignored while one is already running. */
    fun start(pkg: PackageName, mode: CompileMode) {
        if (_state.value.running) return
        _state.value = CompileUiState(running = true, startedAtMs = clock())
        viewModelScope.launch(dispatcher) {
            doCompile(pkg, mode)
        }
    }

    /** Runs the compile pipeline, updates [_state], and returns the outcome. */
    private suspend fun doCompile(pkg: PackageName, mode: CompileMode): CompileEvent.Finished {
        var finished: CompileEvent.Finished? = null
        try {
            runner.run(pkg, mode).collect { e ->
                when (e) {
                    is CompileEvent.Attempt -> append("$ ${e.command}")
                    is CompileEvent.Output -> append(e.line)
                    is CompileEvent.Finished -> finished = e
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            finished = CompileEvent.Finished(false, false, e.message ?: "Error")
        }
        val result = finished ?: CompileEvent.Finished(false, false, "Failure")
        val finishedAt = clock()
        var badge: StatusBadge? = null
        if (result.success) {
            try {
                prefs.setLastCompile(pkg.value, LastCompile(clock(), mode))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // best effort: never leave running = true
            }
            badge = try {
                DexoptParser.badge(checker.check(pkg.value))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                StatusBadge.UNKNOWN
            }
        }
        val offer = result.success && try {
            store?.maintained?.first()?.let { pkg.value !in it } ?: false
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
        _state.value = _state.value.copy(
            running = false, result = result, badge = badge, finishedAtMs = finishedAt, offerKeepOptimized = offer,
        )
        return result
    }

    /**
     * Recompiles quietly (no state or log changes) to restore the app after a
     * failed measurement. Best effort: failures are swallowed by the caller,
     * which already reports the original timing error.
     */
    private suspend fun restoreCompile(pkg: PackageName, mode: CompileMode) {
        try {
            runner.run(pkg, mode).collect { }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Swallowed: the caller reports the timing failure, not the restore.
        }
    }

    /** Ticks [pkg] so the maintenance engine keeps it compiled, then calls [onStored]; no-op without a store. */
    fun keepOptimized(pkg: PackageName, onStored: () -> Unit = {}) {
        val s = store ?: return
        viewModelScope.launch(dispatcher) {
            s.setMaintained(pkg.value, true)
            _state.value = _state.value.copy(offerKeepOptimized = false, kept = true)
            onStored()
        }
    }

    /** Unticks [pkg]; no-op without a store. */
    fun stopKeeping(pkg: PackageName) {
        val s = store ?: return
        viewModelScope.launch(dispatcher) {
            s.setMaintained(pkg.value, false)
            _state.value = _state.value.copy(kept = false)
        }
    }

    /** Reads whether [pkg] is ticked. Call after [reset] when a package is opened. */
    fun loadKept(pkg: PackageName) {
        val s = store ?: return
        viewModelScope.launch(dispatcher) {
            val kept = s.maintained.first().contains(pkg.value)
            _state.value = _state.value.copy(kept = kept)
        }
    }

    /**
     * Loads the launch-timing state for [pkg]: whether SideSpeed can open the
     * app, the stored result, and whether a "before" run is still needed. Call
     * after [reset] when a package is opened.
     */
    fun loadLaunch(pkg: String) {
        if (tools == null) return
        viewModelScope.launch(dispatcher) {
            val cls = launchActivityOf(pkg)
            val ver = versionOf(pkg)
            val stored = try {
                toolsStore?.launchResult(pkg)?.first()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            val needs = if (ver == null) stored == null else needsBeforeMeasure(stored, ver.versionCode, ver.lastUpdateTime)
            val stale = stored != null && ver != null &&
                (stored.versionCode != ver.versionCode || stored.lastUpdateTime != ver.lastUpdateTime)
            _launch.value = LaunchUiState(
                launchable = cls != null, stored = stored, stale = stale, needsBefore = needs, checked = true,
            )
        }
    }

    fun setLaunchChecked(v: Boolean) {
        _launch.value = _launch.value.copy(checked = v)
    }

    fun clearLaunchError() {
        _launch.value = _launch.value.copy(error = null)
    }

    /**
     * The Start-compile button path: times before and after when the checkbox
     * applies, otherwise a plain compile. Never runs from background
     * maintenance — only from this screen.
     */
    fun startMaybeTimed(pkg: PackageName, mode: CompileMode) {
        val l = _launch.value
        if (!l.checked || !l.needsBefore || !l.launchable || tools == null) {
            start(pkg, mode)
            return
        }
        if (_state.value.running || l.busy != null) return
        val cls = launchActivityOf(pkg.value) ?: return
        val ver = versionOf(pkg.value)
        viewModelScope.launch(dispatcher) {
            val timer = LaunchTimer(tools, launchRuns)
            keepAlive()
            setBusy("Timing launch before…")
            val before = when (val r = timer.medianMs(pkg.value, cls)) {
                is ToolResult.Ok -> r.value
                else -> return@launch failTiming(timingError(r))
            }
            setBusy("Compiling…")
            _state.value = CompileUiState(running = true, startedAtMs = clock())
            val result = doCompile(pkg, mode)
            if (!result.success) {
                setBusy(null)
                return@launch
            }
            setBusy("Timing launch after…")
            val after = when (val r = timer.medianMs(pkg.value, cls)) {
                is ToolResult.Ok -> r.value
                else -> return@launch failTiming(timingError(r))
            }
            if (ver != null) storeLaunch(pkg.value, ver, before, after)
            setBusy(null)
        }
    }

    /**
     * Option B: resets the app's compiled code, times it, compiles it back to
     * the app's previous mode (speed when unknown), times again, and stores
     * the pair. On any failure after the reset the app is recompiled first so
     * it is never left in the reset state.
     */
    fun measure(pkg: PackageName) {
        if (_state.value.running || _launch.value.busy != null) return
        val t = tools ?: return
        viewModelScope.launch(dispatcher) {
            val cls = launchActivityOf(pkg.value) ?: run {
                failTiming("This app can't be opened from SideSpeed")
                return@launch
            }
            val ver = versionOf(pkg.value) ?: run {
                failTiming("Couldn't read this app's version")
                return@launch
            }
            val mode = try {
                prefs.lastCompile(pkg.value).first()?.mode
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            } ?: CompileMode.SPEED
            val timer = LaunchTimer(t, launchRuns)
            keepAlive()
            setBusy("Resetting…")
            when (val reset = t.resetCompiled(pkg.value)) {
                is ToolResult.Ok -> {}
                else -> return@launch failTiming(timingError(reset))
            }
            setBusy("Timing launch…")
            val before = when (val r = timer.medianMs(pkg.value, cls)) {
                is ToolResult.Ok -> r.value
                else -> {
                    restoreCompile(pkg, mode)
                    return@launch failTiming(timingError(r))
                }
            }
            setBusy("Compiling…")
            _state.value = CompileUiState(running = true, startedAtMs = clock())
            val result = doCompile(pkg, mode)
            if (!result.success) {
                setBusy(null)
                return@launch
            }
            setBusy("Timing launch again…")
            val after = when (val r = timer.medianMs(pkg.value, cls)) {
                is ToolResult.Ok -> r.value
                else -> return@launch failTiming(timingError(r))
            }
            storeLaunch(pkg.value, ver, before, after)
            setBusy(null)
        }
    }

    private suspend fun storeLaunch(pkg: String, ver: PkgVersion, before: Long, after: Long) {
        val r = LaunchResult(pkg, ver.versionCode, ver.lastUpdateTime, before, after, clock())
        try {
            toolsStore?.setLaunchResult(r)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            failTiming("Couldn't save the result")
            return
        }
        _launch.value = _launch.value.copy(stored = r, stale = false, needsBefore = false)
    }

    private fun setBusy(label: String?) {
        _launch.value = _launch.value.copy(busy = label, error = if (label != null) null else _launch.value.error)
    }

    private fun failTiming(message: String) {
        _launch.value = _launch.value.copy(busy = null, error = message)
    }

    private fun timingError(r: ToolResult<*>): String = when (r) {
        is ToolResult.Ok -> "Couldn't time this app"
        is ToolResult.HelperDown -> "Helper not running"
        is ToolResult.HelperOutdated -> "Restart the helper to use this"
        is ToolResult.Failed -> "Couldn't time this app"
    }

    private fun append(line: String) {
        _state.value = _state.value.let { it.copy(log = (it.log + line).takeLast(MAX_LOG_LINES)) }
    }
}
