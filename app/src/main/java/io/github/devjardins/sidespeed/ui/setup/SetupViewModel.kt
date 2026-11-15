package io.github.devjardins.sidespeed.ui.setup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.devjardins.sidespeed.adb.AdbBackend
import io.github.devjardins.sidespeed.adb.ConnectionStrategy
import io.github.devjardins.sidespeed.adb.Readiness
import io.github.devjardins.sidespeed.data.Prefs
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

sealed interface SetupUiState {
    data object Loading : SetupUiState
    data object NeedsWarningAck : SetupUiState
    data object Checking : SetupUiState
    data object Ready : SetupUiState
    data class Result(val readiness: Readiness, val pairMessage: String? = null, val pairing: Boolean = false) :
        SetupUiState
}

/** What the VM needs from the strategy; lets tests substitute a fake. */
interface Readier {
    suspend fun ensureReady(): Readiness
    suspend fun restartHelper(): Readiness
}

class StrategyReadier(private val s: ConnectionStrategy) : Readier {
    override suspend fun ensureReady() = s.ensureReady()
    override suspend fun restartHelper() = s.restartHelper()
}

class SetupViewModel(
    private val strategy: Readier,
    private val adb: AdbBackend,
    private val prefs: Prefs,
    val isFireOs: Boolean,
    private val wifiIp: () -> String?,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {
    private val _state = MutableStateFlow<SetupUiState>(SetupUiState.Loading)
    val state: StateFlow<SetupUiState> = _state.asStateFlow()
    private var job: Job? = null

    fun wifiIp(): String? = wifiIp.invoke()

    fun start() {
        job?.cancel()
        job = viewModelScope.launch(dispatcher) {
            if (!prefs.lanWarningAcked.first()) {
                _state.value = SetupUiState.NeedsWarningAck
            } else {
                check()
            }
        }
    }

    fun acknowledgeWarning() {
        job?.cancel()
        job = viewModelScope.launch(dispatcher) {
            prefs.setLanWarningAcked(true)
            check()
        }
    }

    fun checkAgain() {
        job?.cancel()
        _state.value = SetupUiState.Checking
        job = viewModelScope.launch(dispatcher) { check() }
    }

    private suspend fun check() {
        _state.value = SetupUiState.Checking
        var r = strategy.ensureReady()
        if (r == Readiness.HelperNeedsRestart && !isFireOs) r = strategy.restartHelper()
        _state.value = if (r == Readiness.HelperReady) SetupUiState.Ready else SetupUiState.Result(r)
    }

    fun pair(code: String, port: String) {
        val current = _state.value as? SetupUiState.Result ?: return
        val p = port.toIntOrNull()
        if (code.length != 6 || p == null) {
            _state.value = current.copy(pairMessage = "Enter the 6-digit code and the pairing port.")
            return
        }
        job?.cancel()
        job = viewModelScope.launch(dispatcher) {
            _state.value = current.copy(pairing = true, pairMessage = "Pairing...")
            var r = adb.pair("127.0.0.1", p, code)
            if (r.isFailure) wifiIp()?.let { r = adb.pair(it, p, code) }
            if (r.isSuccess) {
                prefs.setPairedTls(true)
                check()
            } else {
                _state.value = current.copy(
                    pairMessage = "Pairing failed: ${r.exceptionOrNull()?.message ?: "unknown error"}",
                )
            }
        }
    }
}
