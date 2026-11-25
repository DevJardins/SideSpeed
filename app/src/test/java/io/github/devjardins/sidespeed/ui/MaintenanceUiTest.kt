package io.github.devjardins.sidespeed.ui

import io.github.devjardins.sidespeed.maintenance.AutoOptimizeMode
import io.github.devjardins.sidespeed.maintenance.JobPhase
import io.github.devjardins.sidespeed.maintenance.MaintenanceState
import io.github.devjardins.sidespeed.testing.english
import io.github.devjardins.sidespeed.ui.apps.Pill
import io.github.devjardins.sidespeed.ui.apps.PillTone
import io.github.devjardins.sidespeed.ui.apps.autoOptimizeOption
import io.github.devjardins.sidespeed.ui.apps.maintenanceLine
import io.github.devjardins.sidespeed.ui.apps.maintenancePill
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MaintenanceUiTest {
    private fun st(phases: Map<String, JobPhase> = emptyMap(), maintained: Set<String> = setOf("a.a"), down: Boolean = false) =
        MaintenanceState(maintained, phases, down)

    private fun assertPill(expectedLabel: String, expectedTone: PillTone, actual: Pill?) {
        assertEquals(expectedTone, actual?.tone)
        assertEquals(expectedLabel, actual?.label?.english())
    }

    @Test fun runningPill() = assertPill("Optimizing…", PillTone.NEUTRAL, maintenancePill("a.a", st(mapOf("a.a" to JobPhase.RUNNING))))
    @Test fun waitingPill() = assertPill("Waiting", PillTone.NEUTRAL, maintenancePill("a.a", st(mapOf("a.a" to JobPhase.WAITING))))
    @Test fun retryPill() = assertPill("Retry", PillTone.WARN, maintenancePill("a.a", st(mapOf("a.a" to JobPhase.RETRY))))
    @Test fun optimizedPill() = assertPill("Optimized", PillTone.OK, maintenancePill("a.a", st()))
    @Test fun unmaintainedHasNoPill() = assertNull(maintenancePill("b.b", st()))

    @Test fun lineWaitingForHelper() = assertEquals("Waiting for the helper", maintenanceLine(st(mapOf("a.a" to JobPhase.WAITING), down = true))?.english())
    @Test fun lineCountsQueue() {
        assertEquals(
            "Maintenance: 1 waiting",
            maintenanceLine(st(mapOf("a.a" to JobPhase.RUNNING), maintained = setOf("a.a")))?.english(),
        )
        assertEquals(
            "Maintenance: 2 waiting",
            maintenanceLine(st(mapOf("a.a" to JobPhase.RUNNING, "b.b" to JobPhase.WAITING), maintained = setOf("a.a", "b.b")))?.english(),
        )
    }
    @Test fun lineUpToDate() = assertEquals("Maintenance: up to date", maintenanceLine(st())?.english())
    @Test fun lineHiddenWhenIdle() = assertNull(maintenanceLine(MaintenanceState()))

    @Test fun everyModeHasATitleAndPlainCopy() {
        assertEquals("All apps", autoOptimizeOption(AutoOptimizeMode.ALL).title.english())
        assertEquals("Sideloaded only", autoOptimizeOption(AutoOptimizeMode.SIDELOADED).title.english())
        assertEquals("Selected apps", autoOptimizeOption(AutoOptimizeMode.SELECTED).title.english())
        AutoOptimizeMode.entries.forEach { assertTrue(autoOptimizeOption(it).description.english().length in 20..200) }
    }

    @Test fun selectedCopyTellsWhereToPickApps() =
        assertTrue("Keep this app optimized" in autoOptimizeOption(AutoOptimizeMode.SELECTED).description.english())

    @Test fun sideloadedCopyAdmitsPickedAppsAreIncluded() =
        assertTrue("pick" in autoOptimizeOption(AutoOptimizeMode.SIDELOADED).description.english())

    @Test fun allCopySaysPickedPreinstalledAppsAreIncluded() =
        assertTrue("unless you pick them" in autoOptimizeOption(AutoOptimizeMode.ALL).description.english())
}
