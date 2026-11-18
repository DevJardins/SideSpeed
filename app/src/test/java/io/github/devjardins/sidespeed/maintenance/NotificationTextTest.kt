package io.github.devjardins.sidespeed.maintenance

import io.github.devjardins.sidespeed.tools.CleanupThreshold
import org.junit.Assert.assertEquals
import org.junit.Test

class NotificationTextTest {
    private fun idle(vararg pkgs: String) = MaintenanceState(maintained = pkgs.toSet())

    @Test fun idleTextCountsWhatTheEngineIsKeeping() =
        assertEquals("Keeping 3 apps optimized", notificationText(idle("a.a", "b.b", "c.c"), true, CleanupThreshold.OFF))

    @Test fun singleAppIsNotPlural() =
        assertEquals("Keeping 1 app optimized", notificationText(idle("a.a"), true, CleanupThreshold.OFF))

    @Test fun nothingToKeepYetSaysItIsWatching() =
        assertEquals("Watching for apps to optimize", notificationText(idle(), true, CleanupThreshold.OFF))

    @Test fun queuedWorkShowsProgress() {
        val s = MaintenanceState(setOf("a.a", "b.b"), mapOf("a.a" to JobPhase.RUNNING, "b.b" to JobPhase.WAITING))
        assertEquals("Optimizing apps (2 left)", notificationText(s, true, CleanupThreshold.OFF))
    }

    @Test fun downHelperWithQueuedWorkSaysWaiting() {
        val s = MaintenanceState(setOf("a.a"), mapOf("a.a" to JobPhase.WAITING), helperDown = true)
        assertEquals("Waiting for the helper", notificationText(s, true, CleanupThreshold.OFF))
    }

    @Test fun cleanupOnlyNamesTheThreshold() {
        assertEquals(
            "Keeping free space above 1 GB",
            notificationText(idle(), false, CleanupThreshold.GB_1),
        )
        assertEquals(
            "Keeping free space above 500 MB",
            notificationText(idle(), false, CleanupThreshold.MB_500),
        )
        assertEquals(
            "Keeping free space above 2 GB",
            notificationText(idle(), false, CleanupThreshold.GB_2),
        )
    }

    @Test fun compileAndCleanupKeepsCompileText() {
        assertEquals(
            "Keeping 2 apps optimized",
            notificationText(idle("a.a", "b.b"), true, CleanupThreshold.GB_1),
        )
        assertEquals(
            "Watching for apps to optimize",
            notificationText(idle(), true, CleanupThreshold.GB_1),
        )
    }

    @Test fun queuedWorkWithCleanupOnStillShowsProgress() {
        val s = MaintenanceState(setOf("a.a", "b.b"), mapOf("a.a" to JobPhase.RUNNING, "b.b" to JobPhase.WAITING))
        assertEquals("Optimizing apps (2 left)", notificationText(s, true, CleanupThreshold.GB_1))
    }

    @Test fun nothingArmedAndCleanupOffFallsBackToWatching() =
        assertEquals("Watching for apps to optimize", notificationText(idle(), false, CleanupThreshold.OFF))
}
