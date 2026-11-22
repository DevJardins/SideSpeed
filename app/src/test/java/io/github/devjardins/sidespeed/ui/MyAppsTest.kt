package io.github.devjardins.sidespeed.ui

import io.github.devjardins.sidespeed.apps.AppEntry
import io.github.devjardins.sidespeed.compile.CompileMode
import io.github.devjardins.sidespeed.data.LastCompile
import io.github.devjardins.sidespeed.maintenance.AutoOptimizeMode
import io.github.devjardins.sidespeed.maintenance.JobPhase
import io.github.devjardins.sidespeed.maintenance.MaintenanceState
import io.github.devjardins.sidespeed.ui.apps.AppsFilter
import io.github.devjardins.sidespeed.ui.apps.TOOLS_STATUS
import io.github.devjardins.sidespeed.ui.apps.aboutStatus
import io.github.devjardins.sidespeed.ui.apps.autoOptimizeStatus
import io.github.devjardins.sidespeed.ui.apps.myAppsStatus
import io.github.devjardins.sidespeed.ui.apps.KeepBox
import io.github.devjardins.sidespeed.ui.apps.autoOptimizeButtonLabel
import io.github.devjardins.sidespeed.ui.apps.boxFor
import io.github.devjardins.sidespeed.ui.apps.filterFromSaved
import io.github.devjardins.sidespeed.ui.home.HomeTile
import io.github.devjardins.sidespeed.ui.home.homeTileFromSaved
import io.github.devjardins.sidespeed.ui.apps.initialCompileMode
import io.github.devjardins.sidespeed.ui.apps.lockedMessage
import org.junit.Assert.assertEquals
import org.junit.Test

class MyAppsTest {
    private fun app(pkg: String, installer: String? = null, isSystem: Boolean = false) =
        AppEntry(pkg, pkg, installer, 0L, isSystem)

    @Test fun selectedTickedIsOn() =
        assertEquals(KeepBox.ON, boxFor(app("a.a"), AutoOptimizeMode.SELECTED, setOf("a.a")))

    @Test fun selectedUntickedIsOff() =
        assertEquals(KeepBox.OFF, boxFor(app("a.a"), AutoOptimizeMode.SELECTED, emptySet()))

    @Test fun sideloadedAppLockedUnderSideloaded() =
        assertEquals(KeepBox.LOCKED, boxFor(app("a.a"), AutoOptimizeMode.SIDELOADED, emptySet()))

    @Test fun storeAppUntickedIsOffUnderSideloaded() =
        assertEquals(KeepBox.OFF, boxFor(app("a.a", "com.android.vending"), AutoOptimizeMode.SIDELOADED, emptySet()))

    @Test fun storeAppTickedIsOnUnderSideloaded() =
        assertEquals(KeepBox.ON, boxFor(app("a.a", "com.android.vending"), AutoOptimizeMode.SIDELOADED, setOf("a.a")))

    @Test fun storeAppLockedUnderAll() =
        assertEquals(KeepBox.LOCKED, boxFor(app("a.a", "com.android.vending"), AutoOptimizeMode.ALL, emptySet()))

    @Test fun systemAppUntickedIsOffUnderAll() =
        assertEquals(KeepBox.OFF, boxFor(app("a.a", null, isSystem = true), AutoOptimizeMode.ALL, emptySet()))

    @Test fun systemAppTickedIsOnUnderAll() =
        assertEquals(KeepBox.ON, boxFor(app("a.a", null, isSystem = true), AutoOptimizeMode.ALL, setOf("a.a")))

    @Test fun filterRestoresAll() = assertEquals(AppsFilter.ALL, filterFromSaved("ALL"))

    @Test fun allFilterIsFirst() = assertEquals(listOf(AppsFilter.ALL, AppsFilter.SIDELOADED), AppsFilter.entries)

    @Test fun staleHistoryFilterFallsBackToAll() = assertEquals(AppsFilter.ALL, filterFromSaved("HISTORY"))

    @Test fun missingFilterFallsBackToAll() = assertEquals(AppsFilter.ALL, filterFromSaved(null))

    @Test fun buttonLabelSelected() =
        assertEquals("Auto-optimize: Selected apps", autoOptimizeButtonLabel(AutoOptimizeMode.SELECTED))

    @Test fun buttonLabelSideloaded() =
        assertEquals("Auto-optimize: Sideloaded only", autoOptimizeButtonLabel(AutoOptimizeMode.SIDELOADED))

    @Test fun buttonLabelAll() =
        assertEquals("Auto-optimize: All apps", autoOptimizeButtonLabel(AutoOptimizeMode.ALL))

    @Test fun lockedMessageNamesMode() = assertEquals(
        "Kept optimized by Auto-optimize (All apps). Change it on the Auto-optimize screen.",
        lockedMessage(AutoOptimizeMode.ALL),
    )

    @Test fun noLastCompileMeansSpeed() = assertEquals(CompileMode.SPEED, initialCompileMode(null))

    @Test fun lastCompileModeIsRestored() =
        assertEquals(CompileMode.SPEED_PROFILE, initialCompileMode(LastCompile(1L, CompileMode.SPEED_PROFILE)))

    @Test fun myAppsStatusCountsSideloaded() {
        val apps = listOf(app("a.a"), app("b.b", "com.android.vending"), app("c.c"))
        assertEquals("3 apps · 2 sideloaded", myAppsStatus(apps, loading = false))
    }

    @Test fun myAppsStatusLoading() =
        assertEquals("Loading apps…", myAppsStatus(emptyList(), loading = true))

    @Test fun autoOptimizeStatusUpToDate() =
        assertEquals("All apps · up to date", autoOptimizeStatus(AutoOptimizeMode.ALL, MaintenanceState()))

    @Test fun autoOptimizeStatusWaiting() {
        val state = MaintenanceState(
            phases = mapOf("a.a" to JobPhase.WAITING, "b.b" to JobPhase.WAITING, "c.c" to JobPhase.RUNNING),
        )
        assertEquals("All apps · 3 waiting", autoOptimizeStatus(AutoOptimizeMode.ALL, state))
    }

    @Test fun autoOptimizeStatusHelperDown() {
        val state = MaintenanceState(phases = mapOf("a.a" to JobPhase.WAITING), helperDown = true)
        assertEquals("All apps · waiting for the helper", autoOptimizeStatus(AutoOptimizeMode.ALL, state))
    }

    @Test fun autoOptimizeStatusNullModeIsEmpty() =
        assertEquals("", autoOptimizeStatus(null, MaintenanceState()))

    @Test fun toolsStatusLine() =
        assertEquals("Space, animations, extras, home screen", TOOLS_STATUS)

    @Test fun aboutStatusNamesVersion() = assertEquals("Version 0.7.0", aboutStatus("0.7.0"))

    @Test fun homeTileRestoresTools() = assertEquals(HomeTile.TOOLS, homeTileFromSaved("TOOLS"))

    @Test fun homeTileMissingFallsBackToMyApps() = assertEquals(HomeTile.MY_APPS, homeTileFromSaved(null))

    @Test fun homeTileUnknownFallsBackToMyApps() = assertEquals(HomeTile.MY_APPS, homeTileFromSaved("NOPE"))
}
