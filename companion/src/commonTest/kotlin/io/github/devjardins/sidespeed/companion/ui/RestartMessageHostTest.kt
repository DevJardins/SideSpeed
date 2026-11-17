package io.github.devjardins.sidespeed.companion.ui

import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RestartMessageHostTest {
    @Test fun restartedClearsAfterTwoSeconds() = runTest {
        val host = RestartMessageHost(backgroundScope) { 1_000L }
        host.show("tv1", RestartOutcome.RESTARTED)
        assertEquals(RestartMessage("tv1", RestartOutcome.RESTARTED, 1_000L), host.messages.value["tv1"])
        advanceTimeBy(1_999)
        runCurrent()
        assertEquals(RestartOutcome.RESTARTED, host.messages.value["tv1"]?.outcome, "still showing just before 2 s")
        advanceTimeBy(1)
        runCurrent()
        assertNull(host.messages.value["tv1"], "cleared after 2 s")
    }

    @Test fun notCheckedClearsAfterThreeSeconds() = runTest {
        val host = RestartMessageHost(backgroundScope) { 1_000L }
        host.show("tv1", RestartOutcome.NOT_CHECKED)
        assertEquals(RestartOutcome.NOT_CHECKED, host.messages.value["tv1"]?.outcome)
        advanceTimeBy(2_999)
        runCurrent()
        assertEquals(RestartOutcome.NOT_CHECKED, host.messages.value["tv1"]?.outcome, "still showing just before 3 s")
        advanceTimeBy(1)
        runCurrent()
        assertNull(host.messages.value["tv1"], "cleared after 3 s")
    }

    @Test fun secondPressReplacesTheFirstMessage() = runTest {
        val host = RestartMessageHost(backgroundScope) { testScheduler.currentTime }
        host.show("tv1", RestartOutcome.RESTARTED)
        advanceTimeBy(1_500)
        runCurrent()
        host.show("tv1", RestartOutcome.NOT_CHECKED)
        assertEquals(RestartOutcome.NOT_CHECKED, host.messages.value["tv1"]?.outcome, "the second press replaces the first")
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(
            RestartOutcome.NOT_CHECKED, host.messages.value["tv1"]?.outcome,
            "the first message's 2 s timeout must not clear its replacement",
        )
        advanceTimeBy(2_000)
        runCurrent()
        assertNull(host.messages.value["tv1"], "cleared 3 s after the second press")
    }
}
