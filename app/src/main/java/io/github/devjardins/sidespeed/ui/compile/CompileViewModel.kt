package io.github.devjardins.sidespeed.ui.compile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.devjardins.sidespeed.compile.CompileEvent
import io.github.devjardins.sidespeed.compile.CompileMode
import io.github.devjardins.sidespeed.compile.CompileOutcome
import io.github.devjardins.sidespeed.compile.CompileRunner
import io.github.devjardins.sidespeed.compile.PackageName
import io.github.devjardins.sidespeed.data.LastCompile
import io.github.devjardins.sidespeed.data.Prefs
import io.github.devjardins.sidespeed.maintenance.MaintenanceStore
import io.github.devjardins.sidespeed.status.CompileStatus
import io.github.devjardins.sidespeed.status.DexoptParser
import io.github.devjardins.sidespeed.status.StatusBadge
import io.github.devjardins.sidespeed.status.StatusChecker
import io.github.devjardins.sidespeed.tools.LaunchResult
import io.github.devjardins.sidespeed.tools.LaunchRunner
import io.github.devjardins.sidespeed.tools.needsBeforeMeasure
import io.github.devjardins.sidespeed.ui.text.UiText
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
    val busy: UiText? = null,
    val error: UiText? = null,
)

class CompileViewModel(
    private val runner: CompileRunner,
    private val checker: StatusChecker,
    private val prefs: Prefs,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val store: MaintenanceStore? = null,
    private val launches: LaunchRunner? = null,
    private val clock: () -> Long = System::currentTimeMillis,
    private val status: CompileStatus? = null,
    private val helperUp: suspend () -> Boolean = { true },
) : ViewModel() {
    private val _state = MutableStateFlow(CompileUiState())
    val state: StateFlow<CompileUiState> = _state.asStateFlow()

    private val _launch = MutableStateFlow(LaunchUiState())
    val launch: StateFlow<LaunchUiState> = _launch.asStateFlow()

    private var progressJob: Job? = null
    private var storedJob: Job? = null

    /** Clears the previous run's output (called when a package is opened); ignored while a compile is running. */
    fun reset() {
        if (_state.value.running) return
        _state.value = CompileUiState()
    }

    /** Starts a compile; ignored while one is already running or a launch sequence owns the screen. */
    fun start(pkg: PackageName, mode: CompileMode, label: String) {
        if (_state.value.running || launches?.isBusy() == true) return
        _state.value = CompileUiState(running = true, startedAtMs = clock())
        viewModelScope.launch(dispatcher) {
            doCompile(pkg, mode, label)
        }
    }

    /**
     * Runs the compile pipeline, updates [_state], and returns the outcome. The single choke point
     * for every screen-initiated compile (manual, timed, measure): reports exactly one
     * onStart/onFinish pair per compile here so no path is missed and none double-reports.
     */
    private suspend fun doCompile(pkg: PackageName, mode: CompileMode, label: String): CompileEvent.Finished {
        // Sequences reach here without going through start(), so stamp the start here too.
        _state.value = _state.value.copy(startedAtMs = clock())
        status?.onStart(pkg.value, label, mode.arg)
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
            finished = CompileEvent.Finished(false, false, CompileOutcome.Error(e.message?.takeIf { it.isNotEmpty() }))
        }
        val result = finished ?: CompileEvent.Finished(false, false, CompileOutcome.Failure)
        if (result.success) status?.onFinish(pkg.value, label, true)
        else if (!helperUp()) status?.onHelperLost()
        else status?.onFinish(pkg.value, label, false)
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
        val r = launches ?: return
        progressJob?.cancel()
        progressJob = viewModelScope.launch(dispatcher) {
            r.progressFor(pkg).collect { p -> _launch.value = _launch.value.copy(busy = p?.busy, error = p?.error) }
        }
        storedJob?.cancel()
        storedJob = viewModelScope.launch(dispatcher) {
            val d = r.describe(pkg)
            r.storedResults(pkg).collect { stored ->
                val stale = stored != null && d.version != null &&
                    (stored.versionCode != d.version.versionCode || stored.lastUpdateTime != d.version.lastUpdateTime)
                val needs = if (d.version == null) stored == null
                else needsBeforeMeasure(stored, d.version.versionCode, d.version.lastUpdateTime)
                _launch.value = _launch.value.copy(
                    launchable = d.launchable, stored = stored, stale = stale, needsBefore = needs, checked = true,
                )
            }
        }
    }

    fun setLaunchChecked(v: Boolean) {
        _launch.value = _launch.value.copy(checked = v)
    }

    /**
     * The Start-compile button path: times before and after when the checkbox
     * applies, otherwise a plain compile. Never runs from background
     * maintenance — only from this screen.
     */
    fun startMaybeTimed(pkg: PackageName, mode: CompileMode, label: String): Job? {
        val r = launches
        val l = _launch.value
        if (r == null || !l.checked || !l.needsBefore || !l.launchable || l.busy != null) {
            start(pkg, mode, label)
            return null
        }
        if (_state.value.running) return null
        return r.startTimed(pkg, mode) { p, m -> doCompile(p, m, label) }
    }

    /**
     * Option B: resets the app's compiled code, times it, compiles it back to
     * the app's previous mode (speed when unknown), times again, and stores
     * the pair. On any failure after the reset the app is recompiled first so
     * it is never left in the reset state.
     */
    fun measure(pkg: PackageName, label: String): Job? {
        val r = launches ?: return null
        if (_state.value.running || _launch.value.busy != null) return null
        // The resetCompiled step runs through ToolsCommands, not doCompile, so it never
        // reports; the recompile back reports exactly once via doCompile.
        return r.measure(pkg) { p, m -> doCompile(p, m, label) }
    }

    private fun append(line: String) {
        _state.value = _state.value.let { it.copy(log = (it.log + line).takeLast(MAX_LOG_LINES)) }
    }
}
