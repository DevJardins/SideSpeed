package io.github.devjardins.sidespeed.status

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CompileStatusTest {
    private var now = 1_000_000L
    private fun tracker(version: String = "0.2.1", debuggable: Boolean = false) =
        CompileStatusTracker(version = version, debuggable = debuggable, clock = { now })

    @Test fun idleWhenNothingHappened() {
        val row = tracker().snapshot()
        assertEquals(CompilePhase.IDLE, row.phase)
        assertEquals("", row.activePkg)
        assertEquals("", row.activeLabel)
        assertEquals(0, row.queueWaiting)
        assertNull(row.last)
        assertEquals(SelfState.PENDING, row.self)
        assertEquals("0.2.1", row.version)
    }

    @Test fun startShowsCompilingWithQueue() {
        val t = tracker()
        now = 1_500_000L
        t.onStart("com.example.app", "Example", "speed")
        t.setQueueWaiting(2)
        val row = t.snapshot()
        assertEquals(CompilePhase.COMPILING, row.phase)
        assertEquals("com.example.app", row.activePkg)
        assertEquals("Example", row.activeLabel)
        assertEquals("speed", row.activeMode)
        assertEquals(1_500_000L, row.activeStartedMs)
        assertEquals(2, row.queueWaiting)
    }

    @Test fun finishOkRecordsLastAndGoesIdle() {
        val t = tracker()
        t.onStart("com.example.app", "Example", "speed")
        now = 1_600_000L
        t.onFinish("com.example.app", "Example", true)
        val row = t.snapshot()
        assertEquals(CompilePhase.IDLE, row.phase)
        assertEquals("", row.activePkg)
        assertEquals("com.example.app", row.last?.pkg)
        assertEquals("Example", row.last?.label)
        assertEquals(true, row.last?.ok)
        assertEquals(1_600_000L, row.last?.finishedMs)
    }

    @Test fun finishFailedRecordsFailedResult() {
        val t = tracker()
        t.onStart("com.example.app", "Example", "speed-profile")
        t.onFinish("com.example.app", "Example", false)
        val row = t.snapshot()
        assertEquals(CompilePhase.IDLE, row.phase)
        assertEquals(false, row.last?.ok)
    }

    @Test fun helperLostDuringCompileShowsInterruptedUntilRetryStarts() {
        val t = tracker()
        t.onStart("com.example.app", "Example", "speed")
        t.onHelperLost()
        val interrupted = t.snapshot()
        assertEquals(CompilePhase.INTERRUPTED, interrupted.phase)
        assertEquals("com.example.app", interrupted.activePkg)
        assertEquals("Example", interrupted.activeLabel)
        // The retry starting clears the interruption.
        t.onStart("com.example.app", "Example", "speed")
        assertEquals(CompilePhase.COMPILING, t.snapshot().phase)
    }

    @Test fun helperLostWhileIdleShowsWaitingHelperOnlyWithQueue() {
        val t = tracker()
        t.setQueueWaiting(3)
        t.onHelperLost()
        assertEquals(CompilePhase.WAITING_HELPER, t.snapshot().phase)
        t.setQueueWaiting(0)
        assertEquals(CompilePhase.IDLE, t.snapshot().phase)
    }

    @Test fun helperDownWithoutQueueIsIdle() {
        val t = tracker()
        t.setHelperDown(true)
        assertEquals(CompilePhase.IDLE, t.snapshot().phase)
    }

    @Test fun newStartClearsHelperDown() {
        val t = tracker()
        t.setQueueWaiting(1)
        t.setHelperDown(true)
        assertEquals(CompilePhase.WAITING_HELPER, t.snapshot().phase)
        t.onStart("com.example.app", "Example", "speed")
        assertEquals(CompilePhase.COMPILING, t.snapshot().phase)
    }

    @Test fun exportRestoreMarksInProgressAsInterrupted() {
        val t = tracker()
        t.onStart("com.example.app", "Example", "speed")
        now = 1_700_000L
        t.onFinish("com.example.app", "Example", true)
        val saved = t.export()

        val rebooted = tracker()
        rebooted.restore(saved)
        // The finished compile survives; nothing was in progress, so no interruption.
        assertEquals(CompilePhase.IDLE, rebooted.snapshot().phase)
        assertEquals("com.example.app", rebooted.snapshot().last?.pkg)

        val t2 = tracker()
        now = 1_800_000L
        t2.onStart("com.other.app", "Other", "speed")
        val savedMidCompile = t2.export()

        val rebootedMid = tracker()
        rebootedMid.restore(savedMidCompile)
        val row = rebootedMid.snapshot()
        assertEquals(CompilePhase.INTERRUPTED, row.phase)
        assertEquals("com.other.app", row.activePkg)
        assertEquals("Other", row.activeLabel)
        assertEquals(1_800_000L, row.activeStartedMs)
    }

    @Test fun helperLostPersistsAsInterruptedAcrossRestore() {
        val t = tracker()
        t.onStart("com.example.app", "Example", "speed")
        t.onHelperLost()
        val saved = t.export()
        val rebooted = tracker()
        rebooted.restore(saved)
        val row = rebooted.snapshot()
        assertEquals(CompilePhase.INTERRUPTED, row.phase)
        assertEquals("com.example.app", row.activePkg)
        assertEquals("Example", row.activeLabel)
    }

    @Test fun restoreOfNothingStaysIdle() {
        val t = tracker()
        t.restore(null)
        assertEquals(CompilePhase.IDLE, t.snapshot().phase)
        assertNull(t.export())
    }

    @Test fun selfStates() {
        assertEquals(SelfState.PENDING, tracker().snapshot().self)
        val optimized = tracker()
        optimized.setSelfOptimized(true)
        assertEquals(SelfState.OPTIMIZED, optimized.snapshot().self)
        assertEquals(SelfState.DEBUG, tracker(debuggable = true).snapshot().self)
    }

    @Test fun encodeLabelEscapesCommaEqualsPercent() {
        assertEquals("Plain Name", encodeStatusLabel("Plain Name"))
        assertEquals("A%2C B %3D C%2550", encodeStatusLabel("A, B = C%50"))
        assertEquals("Tab%09Here", encodeStatusLabel("Tab\tHere"))
    }

    @Test fun wireColumnsAreStableAndOrdered() {
        val t = tracker()
        now = 1_500_000L
        t.onStart("com.example.app", "A, B", "speed")
        t.setQueueWaiting(9)
        now = 1_600_000L
        t.onFinish("com.example.app", "A, B", true)
        now = 1_700_000L
        t.onStart("com.other.app", "Other", "speed-profile")
        val cols = t.snapshot().toWireMap()
        assertEquals(
            listOf(
                "v", "state", "pkg", "label", "mode", "started_ms", "queue",
                "last_pkg", "last_label", "last_result", "last_ms", "self", "version",
                "req_seq", "remote", "retrying",
            ),
            cols.map { it.first },
        )
        val byName = cols.toMap()
        assertEquals("1", byName["v"])
        assertEquals("compiling", byName["state"])
        assertEquals("com.other.app", byName["pkg"])
        assertEquals("Other", byName["label"])
        assertEquals("speed-profile", byName["mode"])
        assertEquals("1700000", byName["started_ms"])
        assertEquals("9", byName["queue"])
        assertEquals("com.example.app", byName["last_pkg"])
        assertEquals("A%2C B", byName["last_label"])
        assertEquals("ok", byName["last_result"])
        assertEquals("1600000", byName["last_ms"])
        assertEquals("pending", byName["self"])
        assertEquals("0.2.1", byName["version"])
        assertEquals("0", byName["req_seq"])
        assertEquals("1", byName["remote"])
        assertEquals("0", byName["retrying"])
    }

    @Test fun wire_map_includes_remote_req_seq_and_retrying() {
        val t = tracker()
        val initial = t.snapshot().toWireMap().toMap()
        assertEquals("1", initial["remote"])
        assertEquals("0", initial["req_seq"])
        assertEquals("0", initial["retrying"])

        assertEquals(0L, t.requestSeq())
        assertEquals(1L, t.bumpRequestSeq())
        assertEquals(2L, t.bumpRequestSeq())
        assertEquals(2L, t.requestSeq())

        t.setRetrying(2)
        val updated = t.snapshot().toWireMap().toMap()
        assertEquals("1", updated["remote"])
        assertEquals("2", updated["req_seq"])
        assertEquals("2", updated["retrying"])

        t.setRetrying(-3)
        assertEquals("0", t.snapshot().toWireMap().toMap()["retrying"])

        val noRemote = t.snapshot().copy(remoteSupported = false).toWireMap().toMap()
        assertEquals("0", noRemote["remote"])
    }

    @Test fun interruptedRowKeepsStartedMs() {
        val t = tracker()
        now = 2_000_000L
        t.onStart("com.example.app", "Example", "speed")
        t.onHelperLost()
        assertEquals("2000000", t.snapshot().toWireMap().toMap()["started_ms"])
    }

    @Test fun isActive_true_only_while_a_compile_runs() {
        val t = tracker()
        assertFalse(t.isActive())
        t.onStart("com.example.app", "Example", "speed")
        assertTrue(t.isActive())
        t.onHelperLost()
        assertFalse(t.isActive())
        t.onStart("com.example.app", "Example", "speed")
        t.onFinish("com.example.app", "Example", true)
        assertFalse(t.isActive())
    }

    @Test fun glue_active_flow_tracks_running_compile() {
        val status = CompileStatus(
            FakeCompileStatusStore(), "0.2.1", false,
            scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined),
        )
        assertFalse(status.active.value)
        status.onStart("com.example.app", "Example", "speed")
        assertTrue(status.active.value)
        status.onFinish("com.example.app", "Example", true)
        assertFalse(status.active.value)
    }

    @Test fun glue_request_seq_and_retrying_are_immediately_visible() = kotlinx.coroutines.test.runTest {
        val status = CompileStatus(
            FakeCompileStatusStore(), "0.2.1", false,
            scope = backgroundScope,
        )
        assertEquals(0L, status.requestSeq())
        assertEquals(1L, status.bumpRequestSeq())
        assertEquals(1L, status.requestSeq())
        status.setRetrying(3)
        val row = status.snapshot()
        assertEquals(1L, row.reqSeq)
        assertEquals(3, row.retrying)
        assertTrue(row.remoteSupported)
    }
}
