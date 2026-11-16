package io.github.devjardins.sidespeed.ui.compile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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

class CompileViewModel(
    private val runner: CompileRunner,
    private val checker: StatusChecker,
    private val prefs: Prefs,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val store: MaintenanceStore? = null,
    private val clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {
    private val _state = MutableStateFlow(CompileUiState())
    val state: StateFlow<CompileUiState> = _state.asStateFlow()

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
        }
    }

    /** Ticks [pkg] so the maintenance engine keeps it compiled; no-op without a store. */
    fun keepOptimized(pkg: PackageName) {
        val s = store ?: return
        viewModelScope.launch(dispatcher) {
            s.setMaintained(pkg.value, true)
            _state.value = _state.value.copy(offerKeepOptimized = false, kept = true)
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

    private fun append(line: String) {
        _state.value = _state.value.let { it.copy(log = (it.log + line).takeLast(MAX_LOG_LINES)) }
    }
}
