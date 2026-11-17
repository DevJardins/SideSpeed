package io.github.devjardins.sidespeed.companion.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One finished-restart outcome shown on a TV's card until its timeout clears it. */
data class RestartMessage(val tvId: String, val outcome: RestartOutcome, val shownAtMs: Long)

/**
 * Holds the finished-restart message per card. [show] replaces any message already showing on that
 * card — so only one outcome is ever visible per card — and clears it after its timeout (2 s for
 * [RestartOutcome.RESTARTED], 3 s for [RestartOutcome.NOT_CHECKED]). A null outcome clears the card
 * at once. The timeout lives in this holder rather than in a `LaunchedEffect` keyed on the restart,
 * so ending the in-flight state can't cancel the clearing.
 */
class RestartMessageHost(
    private val scope: CoroutineScope,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private val _messages = MutableStateFlow<Map<String, RestartMessage>>(emptyMap())
    val messages: StateFlow<Map<String, RestartMessage>> = _messages.asStateFlow()

    private val jobs = mutableMapOf<String, Job>()

    fun show(tvId: String, outcome: RestartOutcome?) {
        jobs.remove(tvId)?.cancel()
        if (outcome == null) {
            _messages.update { it - tvId }
            return
        }
        val message = RestartMessage(tvId, outcome, clock())
        _messages.update { it + (tvId to message) }
        // Only these two outcomes are ever shown (see HomeScreen); anything else clears at once.
        val timeoutMs = when (outcome) {
            RestartOutcome.RESTARTED -> 2_000L
            RestartOutcome.NOT_CHECKED -> 3_000L
            RestartOutcome.CHECKED -> 0L
        }
        if (timeoutMs <= 0L) {
            _messages.update { it - tvId }
            return
        }
        jobs[tvId] = scope.launch {
            delay(timeoutMs)
            _messages.update { current ->
                // A newer press replaced this message: leave the replacement alone.
                if (current[tvId] == message) current - tvId else current
            }
            jobs.remove(tvId)
        }
    }
}
