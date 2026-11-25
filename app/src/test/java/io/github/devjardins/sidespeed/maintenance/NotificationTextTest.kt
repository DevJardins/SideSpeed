package io.github.devjardins.sidespeed.maintenance

import io.github.devjardins.sidespeed.testing.english
import io.github.devjardins.sidespeed.tools.CleanupThreshold
import org.junit.Assert.assertEquals
import org.junit.Test

class NotificationTextTest {
    private fun idle(vararg pkgs: String) = MaintenanceState(maintained = pkgs.toSet())

    @Test fun idleTextCountsWhatTheEngineIsKeeping() =
        assertEquals("Keeping 3 apps optimized", notificationText(idle("a.a", "b.b", "c.c"), true, CleanupThreshold.OFF).english())

    @Test fun singleAppIsNotPlural() =
        assertEquals("Keeping 1 app optimized", notificationText(idle("a.a"), true, CleanupThreshold.OFF).english())

    @Test fun nothingToKeepYetSaysItIsWatching() =
        assertEquals("Watching for apps to optimize", notificationText(idle(), true, CleanupThreshold.OFF).english())

    @Test fun queuedWorkShowsProgress() {
        val s = MaintenanceState(setOf("a.a", "b.b"), mapOf("a.a" to JobPhase.RUNNING, "b.b" to JobPhase.WAITING))
        assertEquals("Optimizing apps (2 left)", notificationText(s, true, CleanupThreshold.OFF).english())
    }

    @Test fun downHelperWithQueuedWorkSaysWaiting() {
        val s = MaintenanceState(setOf("a.a"), mapOf("a.a" to JobPhase.WAITING), helperDown = true)
        assertEquals("Waiting for the helper", notificationText(s, true, CleanupThreshold.OFF).english())
    }

    @Test fun cleanupOnlyNamesTheThreshold() {
        assertEquals(
            "Keeping free space above 1 GB",
            notificationText(idle(), false, CleanupThreshold.GB_1).english(),
        )
        assertEquals(
            "Keeping free space above 500 MB",
            notificationText(idle(), false, CleanupThreshold.MB_500).english(),
        )
        assertEquals(
            "Keeping free space above 2 GB",
            notificationText(idle(), false, CleanupThreshold.GB_2).english(),
        )
    }

    @Test fun compileAndCleanupKeepsCompileText() {
        assertEquals(
            "Keeping 2 apps optimized",
            notificationText(idle("a.a", "b.b"), true, CleanupThreshold.GB_1).english(),
        )
        assertEquals(
            "Watching for apps to optimize",
            notificationText(idle(), true, CleanupThreshold.GB_1).english(),
        )
    }

    @Test fun queuedWorkWithCleanupOnStillShowsProgress() {
        val s = MaintenanceState(setOf("a.a", "b.b"), mapOf("a.a" to JobPhase.RUNNING, "b.b" to JobPhase.WAITING))
        assertEquals("Optimizing apps (2 left)", notificationText(s, true, CleanupThreshold.GB_1).english())
    }

    @Test fun nothingArmedAndCleanupOffFallsBackToWatching() =
        assertEquals("Watching for apps to optimize", notificationText(idle(), false, CleanupThreshold.OFF).english())

    @Test fun counts_read_as_before() {
        // Maintained counts: 0, 1, 2
        assertEquals("Watching for apps to optimize", notificationText(idle(), true, CleanupThreshold.OFF).english())
        assertEquals("Keeping 1 app optimized", notificationText(idle("a.a"), true, CleanupThreshold.OFF).english())
        assertEquals("Keeping 2 apps optimized", notificationText(idle("a.a", "b.b"), true, CleanupThreshold.OFF).english())

        // Queue counts: 0, 1, 2
        assertEquals("Keeping 2 apps optimized", notificationText(idle("a.a", "b.b"), true, CleanupThreshold.OFF).english())
        val q1 = MaintenanceState(setOf("a.a", "b.b"), mapOf("a.a" to JobPhase.RUNNING))
        assertEquals("Optimizing apps (1 left)", notificationText(q1, true, CleanupThreshold.OFF).english())
        val q2 = MaintenanceState(setOf("a.a", "b.b"), mapOf("a.a" to JobPhase.RUNNING, "b.b" to JobPhase.WAITING))
        assertEquals("Optimizing apps (2 left)", notificationText(q2, true, CleanupThreshold.OFF).english())
    }
}
