package io.github.devjardins.sidespeed.baselineprofile

import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private const val PACKAGE = "io.github.devjardins.sidespeed"
private const val WAIT_MS = 10_000L
private const val PAIR_WAIT_MS = 30_000L

/**
 * Records SideSpeed's critical journey into the baseline profile. Run on a connected device with
 * SideSpeed installable (a debug-key-signed build is fine):
 * `./gradlew :baselineprofile:collectNonMinifiedReleaseBaselineProfile`.
 * An instrumented test, so it never runs in `./gradlew build` or `./gradlew test`; CI stays green
 * without a device. Past Setup the Apps list is the launch screen; on a fresh install the generator
 * clicks through Setup itself (the warning and the app's own ADB-key prompt).
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun generate() = rule.collect(PACKAGE, includeInStartupProfile = true) {
        // The rule measures but never launches: start the app for every iteration.
        pressHome()
        startActivityAndWait()
        enterAppsList()
        // Focus starts on the active tab: make sure that is Sideloaded (extra Lefts at the edge
        // do nothing), step right to All apps (which has rows even with no sideloaded apps) and
        // select it, then step down into the list.
        repeat(3) { device.pressDPadLeft() }
        device.pressDPadRight()
        device.pressDPadCenter()
        device.waitForIdle()
        device.pressDPadDown()
        // Scroll the Apps list down and back up with the D-pad.
        repeat(15) { device.pressDPadDown() }
        repeat(15) { device.pressDPadUp() }
        // Open one app's compile screen and back out.
        device.pressDPadDown()
        device.pressDPadCenter()
        device.waitForIdle()
        device.pressBack()
    }

    private fun MacrobenchmarkScope.enterAppsList() {
        if (device.wait(Until.hasObject(By.textContains("Pick an app")), WAIT_MS)) return
        // Fresh install: acknowledge the warning, then approve the app's own ADB key when the
        // system prompts for it (the retry loop reconnects until it is approved).
        clickFirst("I understand")
        if (device.wait(Until.hasObject(By.textContains("Allow USB debugging")), PAIR_WAIT_MS)) {
            clickFirst("Always allow from this computer")
            clickFirst("Allow")
        }
        device.wait(Until.hasObject(By.textContains("Pick an app")), WAIT_MS)
    }

    private fun MacrobenchmarkScope.clickFirst(text: String) {
        device.findObjects(By.text(text)).firstOrNull()?.click()
    }
}
