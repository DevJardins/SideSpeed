package io.github.devjardins.sidespeed.maintenance

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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
