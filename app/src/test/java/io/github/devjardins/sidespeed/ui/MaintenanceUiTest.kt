package io.github.devjardins.sidespeed.ui

import io.github.devjardins.sidespeed.maintenance.JobPhase
import io.github.devjardins.sidespeed.maintenance.MaintenanceState
import io.github.devjardins.sidespeed.ui.apps.Pill
import io.github.devjardins.sidespeed.ui.apps.PillTone
import io.github.devjardins.sidespeed.ui.apps.maintenanceLine
import io.github.devjardins.sidespeed.ui.apps.maintenancePill
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MaintenanceUiTest {
    private fun st(phases: Map<String, JobPhase> = emptyMap(), maintained: Set<String> = setOf("a.a"), down: Boolean = false) =
        MaintenanceState(maintained, phases, down)

    @Test fun runningPill() = assertEquals(Pill("Optimizing…", PillTone.NEUTRAL), maintenancePill("a.a", st(mapOf("a.a" to JobPhase.RUNNING))))
    @Test fun waitingPill() = assertEquals(Pill("Waiting", PillTone.NEUTRAL), maintenancePill("a.a", st(mapOf("a.a" to JobPhase.WAITING))))
    @Test fun retryPill() = assertEquals(Pill("Retry", PillTone.WARN), maintenancePill("a.a", st(mapOf("a.a" to JobPhase.RETRY))))
    @Test fun optimizedPill() = assertEquals(Pill("Optimized", PillTone.OK), maintenancePill("a.a", st()))
    @Test fun unmaintainedHasNoPill() = assertNull(maintenancePill("b.b", st()))

    @Test fun lineWaitingForHelper() = assertEquals("Waiting for the helper", maintenanceLine(st(mapOf("a.a" to JobPhase.WAITING), down = true)))
    @Test fun lineCountsQueue() = assertEquals(
        "Maintenance: 2 waiting",
        maintenanceLine(st(mapOf("a.a" to JobPhase.RUNNING, "b.b" to JobPhase.WAITING), maintained = setOf("a.a", "b.b"))),
    )
    @Test fun lineUpToDate() = assertEquals("Maintenance: up to date", maintenanceLine(st()))
    @Test fun lineHiddenWhenIdle() = assertNull(maintenanceLine(MaintenanceState()))
}
