package io.github.devjardins.sidespeed.status

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
    }

    @Test fun interruptedRowKeepsStartedMs() {
        val t = tracker()
        now = 2_000_000L
        t.onStart("com.example.app", "Example", "speed")
        t.onHelperLost()
        assertEquals("2000000", t.snapshot().toWireMap().toMap()["started_ms"])
    }
}
