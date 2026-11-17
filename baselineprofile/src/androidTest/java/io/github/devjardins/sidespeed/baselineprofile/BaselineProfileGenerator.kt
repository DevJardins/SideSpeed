package io.github.devjardins.sidespeed.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private const val PACKAGE = "io.github.devjardins.sidespeed"
private const val WAIT_MS = 10_000L

/**
 * Records SideSpeed's critical journey into the baseline profile. Run on a device with SideSpeed
 * installed and its helper paired (the Apps list is the launch screen only past Setup):
 * `./gradlew :baselineprofile:generateBaselineProfile`. An instrumented test, so it never runs in
 * `./gradlew build` or `./gradlew test`; CI stays green without a device.
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun generate() = rule.collect(PACKAGE, includeInStartupProfile = true) {
        // Best effort: if Setup is showing instead of the Apps list, the waits below time out and the
        // run still records whatever paths executed.
        device.wait(Until.hasObject(By.textContains("Pick an app")), WAIT_MS)
        // Scroll the Apps list down and back up with the D-pad.
        repeat(15) { device.pressDPadDown() }
        repeat(15) { device.pressDPadUp() }
        // Open one app's compile screen and back out.
        device.pressDPadDown()
        device.pressDPadCenter()
        device.waitForIdle()
        device.pressBack()
    }
}
