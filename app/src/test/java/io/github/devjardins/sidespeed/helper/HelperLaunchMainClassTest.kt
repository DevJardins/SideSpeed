package io.github.devjardins.sidespeed.helper

import org.junit.Assert.assertEquals
import org.junit.Test

/** Stays in :app because [HelperMain] lives here; the rest of HelperLaunchTest is in :shared. */
class HelperLaunchMainClassTest {
    @Test fun namesTheRealMainClass() =
        assertEquals(true, HelperLaunch.command().contains(" ${HelperMain::class.java.name} "))
}
