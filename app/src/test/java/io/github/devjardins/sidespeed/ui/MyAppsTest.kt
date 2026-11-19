package io.github.devjardins.sidespeed.ui

import io.github.devjardins.sidespeed.apps.AppEntry
import io.github.devjardins.sidespeed.compile.CompileMode
import io.github.devjardins.sidespeed.data.LastCompile
import io.github.devjardins.sidespeed.maintenance.AutoOptimizeMode
import io.github.devjardins.sidespeed.ui.apps.AppsFilter
import io.github.devjardins.sidespeed.ui.apps.KeepBox
import io.github.devjardins.sidespeed.ui.apps.autoOptimizeButtonLabel
import io.github.devjardins.sidespeed.ui.apps.boxFor
import io.github.devjardins.sidespeed.ui.apps.filterFromSaved
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

    @Test fun staleHistoryFilterFallsBackToSideloaded() = assertEquals(AppsFilter.SIDELOADED, filterFromSaved("HISTORY"))

    @Test fun missingFilterFallsBackToSideloaded() = assertEquals(AppsFilter.SIDELOADED, filterFromSaved(null))

    @Test fun buttonLabelSelected() =
        assertEquals("Auto-optimize: Selected apps", autoOptimizeButtonLabel(AutoOptimizeMode.SELECTED))

    @Test fun buttonLabelSideloaded() =
        assertEquals("Auto-optimize: Sideloaded only", autoOptimizeButtonLabel(AutoOptimizeMode.SIDELOADED))

    @Test fun buttonLabelAll() =
        assertEquals("Auto-optimize: All apps", autoOptimizeButtonLabel(AutoOptimizeMode.ALL))

    @Test fun lockedMessageNamesMode() = assertEquals(
        "Kept optimized by Auto-optimize (All apps). Change it with the Auto-optimize button.",
        lockedMessage(AutoOptimizeMode.ALL),
    )

    @Test fun noLastCompileMeansSpeed() = assertEquals(CompileMode.SPEED, initialCompileMode(null))

    @Test fun lastCompileModeIsRestored() =
        assertEquals(CompileMode.SPEED_PROFILE, initialCompileMode(LastCompile(1L, CompileMode.SPEED_PROFILE)))
}
