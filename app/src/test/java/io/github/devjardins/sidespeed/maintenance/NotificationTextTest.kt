package io.github.devjardins.sidespeed.maintenance

import org.junit.Assert.assertEquals
import org.junit.Test

class NotificationTextTest {
    private fun idle(vararg pkgs: String) = MaintenanceState(maintained = pkgs.toSet())

    @Test fun idleTextCountsWhatTheEngineIsKeeping() =
        assertEquals("Keeping 3 apps optimized", notificationText(idle("a.a", "b.b", "c.c")))

    @Test fun singleAppIsNotPlural() = assertEquals("Keeping 1 app optimized", notificationText(idle("a.a")))

    @Test fun allOrSideloadedModeWithNoMatchingAppsCountsZero() =
        assertEquals("Keeping 0 apps optimized", notificationText(idle()))

    @Test fun queuedWorkShowsProgress() {
        val s = MaintenanceState(setOf("a.a", "b.b"), mapOf("a.a" to JobPhase.RUNNING, "b.b" to JobPhase.WAITING))
        assertEquals("Optimizing apps (2 left)", notificationText(s))
    }

    @Test fun downHelperWithQueuedWorkSaysWaiting() {
        val s = MaintenanceState(setOf("a.a"), mapOf("a.a" to JobPhase.WAITING), helperDown = true)
        assertEquals("Waiting for the helper", notificationText(s))
    }
}
