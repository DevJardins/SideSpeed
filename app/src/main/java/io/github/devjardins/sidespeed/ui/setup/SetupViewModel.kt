package io.github.devjardins.sidespeed.ui.setup

import android.content.ContentResolver
import android.provider.Settings
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.devjardins.sidespeed.R
import io.github.devjardins.sidespeed.adb.AdbBackend
import io.github.devjardins.sidespeed.adb.AdbPairingFailedException
import io.github.devjardins.sidespeed.adb.ConnectionStrategy
import io.github.devjardins.sidespeed.adb.Readiness
import io.github.devjardins.sidespeed.adb.StartCause
import io.github.devjardins.sidespeed.data.Prefs
import io.github.devjardins.sidespeed.ui.text.UiText
import io.github.devjardins.sidespeed.ui.text.uiText
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

sealed interface SetupUiState {
    data object Loading : SetupUiState
    data object NeedsWarningAck : SetupUiState
    data object Ready : SetupUiState

    /**
     * The step cards. [detected]: step 1 (debugging switched on). [waiting]: the retry loop is running (step 2,
     * "Waiting for you…"). [launching]: ADB is connected and the helper is being started (step 3).
     * [readiness]: the last non-ready result. [refusedHint]: the classic port refused 3 times in a row while
     * Wireless debugging was on. [showPairing]: the pairing pads, only ever shown on request.
     */
    data class Connecting(
        val detected: Boolean = false,
        val waiting: Boolean = true,
        val launching: Boolean = false,
        val readiness: Readiness? = null,
        val refusedHint: Boolean = false,
        val showPairing: Boolean = false,
        val pairing: Boolean = false,
        val pairMessage: UiText? = null,
    ) : SetupUiState
}

/** What the VM needs from the strategy; lets tests substitute a fake. */
interface Readier {
    suspend fun ensureReady(onLaunch: () -> Unit = {}): Readiness
    suspend fun restartHelper(onLaunch: () -> Unit = {}): Readiness
}

class StrategyReadier(private val s: ConnectionStrategy) : Readier {
    override suspend fun ensureReady(onLaunch: () -> Unit) = s.ensureReady(onLaunch)
    override suspend fun restartHelper(onLaunch: () -> Unit) = s.restartHelper(onLaunch)
}

/** Reads `Settings.Global` integers (0 when unset). Readable by any app, no permission needed. */
fun interface SettingsReader {
    fun getInt(name: String): Int

    companion object {
        const val ADB_ENABLED = "adb_enabled"
        const val ADB_WIFI_ENABLED = "adb_wifi_enabled"

        fun of(resolver: ContentResolver) = SettingsReader { name ->
            try {
                Settings.Global.getInt(resolver, name, 0)
            } catch (_: Exception) {
                0
            }
        }
    }
}

class SetupViewModel(
    private val strategy: Readier,
    private val adb: AdbBackend,
    private val prefs: Prefs,
    val isFireOs: Boolean,
    private val wifiIp: () -> String?,
    private val settings: SettingsReader,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {
    private val _state = MutableStateFlow<SetupUiState>(SetupUiState.Loading)
    val state: StateFlow<SetupUiState> = _state.asStateFlow()

    /** The one running loop / pairing job. Replaced only through [launchExclusive]. */
    private var job: Job? = null
    private var refusals = 0

    fun wifiIp(): String? = wifiIp.invoke()

    /** Called when the setup screen appears. Starts (or restarts) the retry loop once the warning is acked. */
    fun start() {
        refusals = 0
        if (_state.value == SetupUiState.Ready) _state.value = SetupUiState.Connecting()
        launchExclusive {
            if (!prefs.lanWarningAcked.first()) {
                _state.value = SetupUiState.NeedsWarningAck
            } else {
                retryLoop()
            }
        }
    }

    fun acknowledgeWarning() {
        launchExclusive {
            prefs.setLanWarningAcked(true)
            retryLoop()
        }
    }

    /** Leaves a stale Ready at once (so the screen does not bounce back), then restarts the loop. */
    fun checkAgain() {
        _state.update { it as? SetupUiState.Connecting ?: SetupUiState.Connecting() }
        launchExclusive { retryLoop() }
    }

    /** Called when the setup screen leaves composition. A pending attempt finishes on its own. */
    fun stopRetrying() {
        job?.cancel()
    }

    fun showPairing() = updateConnecting { it.copy(showPairing = true) }

    fun hidePairing() = updateConnecting { it.copy(showPairing = false) }

    /**
     * Starts [block] only after the previous job has fully finished, so two ADB connects (or a connect and a
     * pairing) never run at once: a blocked connect ignores cancellation until libadb returns. The wait is
     * non-cancellable so a job replaced while it is still waiting keeps the chain intact (otherwise it would
     * finish early and let the next job start beside a pending connect).
     */
    private fun launchExclusive(block: suspend () -> Unit) {
        val previous = job
        // ATOMIC: a job cancelled before it is dispatched still runs up to the wait below, so it never
        // completes ahead of the job it replaced.
        job = viewModelScope.launch(dispatcher, start = CoroutineStart.ATOMIC) {
            withContext(NonCancellable) { previous?.cancelAndJoin() }
            ensureActive()
            block()
        }
    }

    private fun updateConnecting(f: (SetupUiState.Connecting) -> SetupUiState.Connecting) {
        _state.update { f(it as? SetupUiState.Connecting ?: SetupUiState.Connecting()) }
    }

    private fun detected(): Boolean =
        if (isFireOs) {
            settings.getInt(SettingsReader.ADB_ENABLED) == 1
        } else {
            settings.getInt(SettingsReader.ADB_WIFI_ENABLED) == 1 || settings.getInt(SettingsReader.ADB_ENABLED) == 1
        }

    /** Attempt, then a [RETRY_DELAY_MS] pause, then the next attempt, until ready. Never overlaps attempts. */
    private suspend fun retryLoop() {
        val markLaunching = { updateConnecting { it.copy(launching = true) } }
        // The loop never runs beside a pairing, so a pairing flag still set here belongs to a cancelled job.
        updateConnecting { if (it.pairing) it.copy(pairing = false, pairMessage = null) else it }
        while (true) {
            val before = detected()
            updateConnecting { it.copy(detected = before, waiting = true, launching = false) }
            var r = strategy.ensureReady(markLaunching)
            if (r == Readiness.HelperNeedsRestart && !isFireOs) r = strategy.restartHelper(markLaunching)
            if (r == Readiness.HelperReady) {
                refusals = 0
                _state.value = SetupUiState.Ready
                return
            }
            val on = detected()
            val refused = r is Readiness.NeedsHelperStart && r.cause == StartCause.REFUSED
            refusals = if (refused && on && !isFireOs) refusals + 1 else 0
            updateConnecting {
                it.copy(detected = on, launching = false, readiness = r, refusedHint = refusals >= REFUSALS_FOR_HINT)
            }
            delay(RETRY_DELAY_MS)
        }
    }

    fun pair(code: String, port: String) {
        if (_state.value !is SetupUiState.Connecting) return
        val p = port.toIntOrNull()
        if (code.length != 6 || p == null) {
            updateConnecting { it.copy(pairMessage = UiText.Res(R.string.setup_pairing_invalid_input)) }
            return
        }
        // A pending check (up to 30 s while the Allow prompt is up) must finish first; say so.
        updateConnecting { it.copy(pairing = true, pairMessage = UiText.Res(R.string.setup_pairing_waiting_check)) }
        launchExclusive {
            updateConnecting { it.copy(pairMessage = UiText.Res(R.string.setup_pairing_in_progress)) }
            val r = try {
                // The timeout starts only now that pairing actually begins.
                withTimeout(PAIR_TIMEOUT_MS) {
                    var r = adb.pair("127.0.0.1", p, code)
                    if (r.isFailure) wifiIp()?.let { r = adb.pair(it, p, code) }
                    r
                }
            } catch (_: TimeoutCancellationException) {
                null
            } finally {
                // Cancelled (screen left) or done: never leave the Pair button disabled.
                updateConnecting { if (it.pairing) it.copy(pairing = false, pairMessage = null) else it }
            }
            when {
                r == null -> updateConnecting { it.copy(pairMessage = UiText.Res(R.string.setup_pairing_timed_out)) }
                r.isSuccess -> {
                    prefs.setPairedTls(true)
                    updateConnecting { it.copy(pairMessage = UiText.Res(R.string.setup_pairing_paired_connecting)) }
                }
                else -> updateConnecting {
                    it.copy(pairMessage = pairFailureMessage(r.exceptionOrNull()))
                }
            }
            retryLoop()
        }
    }

    private fun pairFailureMessage(err: Throwable?): UiText = when (err) {
        is AdbPairingFailedException -> UiText.Res(R.string.setup_pairing_failed_adb)
        else -> {
            val msg = err?.message
            if (msg == null) {
                UiText.Res(R.string.setup_pairing_failed_unknown)
            } else {
                uiText(R.string.setup_pairing_failed, UiText.Raw(msg))
            }
        }
    }

    companion object {
        const val PAIR_TIMEOUT_MS = 30_000L
        const val RETRY_DELAY_MS = 2_000L
        const val REFUSALS_FOR_HINT = 3
    }
}
