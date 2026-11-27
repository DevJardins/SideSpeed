package io.github.devjardins.sidespeed.maintenance

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

@OptIn(ExperimentalCoroutinesApi::class)
class RemoteCompileJobTest {
    @Test fun awaitsDrainUntilQueueEmpty() = runTest {
        val state = MutableStateFlow(MaintenanceState(phases = mapOf("a.a" to JobPhase.RUNNING)))
        val deferred = async { awaitRemoteCompileDrain(state, timeoutMs = 10_000L) }
        runCurrent()
        assertFalse(deferred.isCompleted)

        state.value = MaintenanceState(phases = emptyMap())
        runCurrent()
        assertTrue(deferred.isCompleted)
    }

    @Test fun exitsEarlyWhenHelperGoesDown() = runTest {
        val state = MutableStateFlow(
            MaintenanceState(phases = mapOf("a.a" to JobPhase.WAITING), helperDown = false),
        )
        val deferred = async { awaitRemoteCompileDrain(state, timeoutMs = 10_000L) }
        runCurrent()
        assertFalse(deferred.isCompleted)

        state.value = state.value.copy(helperDown = true)
        runCurrent()
        assertTrue(deferred.isCompleted)
    }

    @Test fun finish_gate_drops_late_finish_after_stop() {
        val gate = JobFinishGate()
        val key = Any()
        // No stop recorded: the finish still reports.
        assertTrue(gate.claimFinish(key))
        // Stopped first: the late finish is dropped, and the stop is consumed.
        gate.markStopped(key)
        assertFalse(gate.claimFinish(key))
        // A restarted job with the same key reports again.
        assertTrue(gate.claimFinish(key))
        // Stops are keyed: another job is unaffected.
        gate.markStopped(Any())
        assertTrue(gate.claimFinish(key))
    }

    @Test fun finish_gate_is_safe_under_racing_stop_and_finish() {
        repeat(200) {
            val gate = JobFinishGate()
            val key = Any()
            val result = AtomicReference<Boolean>()
            val go = CountDownLatch(1)
            val finisher = thread { go.await(); result.set(gate.claimFinish(key)) }
            val stopper = thread { go.await(); gate.markStopped(key) }
            go.countDown()
            finisher.join(5_000)
            stopper.join(5_000)
            assertFalse(finisher.isAlive)
            assertFalse(stopper.isAlive)
            assertNotNull(result.get())
        }
    }

    @Test fun returnsCleanlyWhenTimeoutExpires() = runTest {
        val state = MutableStateFlow(MaintenanceState(phases = mapOf("a.a" to JobPhase.RUNNING)))
        val deferred = async { awaitRemoteCompileDrain(state, timeoutMs = 5_000L) }
        runCurrent()
        assertFalse(deferred.isCompleted)

        advanceTimeBy(5_000L)
        runCurrent()
        assertTrue(deferred.isCompleted)
        assertEquals(4103, RemoteCompileJob.JOB_ID_REMOTE_COMPILE)
    }
}
