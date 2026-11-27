package io.github.devjardins.sidespeed.helper

import org.junit.Assert.assertEquals
import org.junit.Test

/** Stays in :app because [HelperMain] lives here; the rest of HelperLaunchTest is in :shared. */
class HelperLaunchMainClassTest {
    @Test fun namesTheRealMainClass() {
        for (pkg in SideSpeedPackages.ALL) {
            assertEquals(true, HelperLaunch.command(pkg).contains(" ${HelperMain::class.java.name} "))
        }
    }
}
