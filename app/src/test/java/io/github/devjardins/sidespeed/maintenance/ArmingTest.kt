package io.github.devjardins.sidespeed.maintenance

import io.github.devjardins.sidespeed.tools.CleanupThreshold
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArmingTest {
    @Test fun serviceNotNeededWhenNothingArmedAndCleanupOff() {
        assertFalse(isServiceNeeded(AutoOptimizeMode.SELECTED, emptySet(), CleanupThreshold.OFF))
    }

    @Test fun serviceNeededWhenTicked() {
        assertTrue(isServiceNeeded(AutoOptimizeMode.SELECTED, setOf("com.x.y"), CleanupThreshold.OFF))
    }

    @Test fun serviceNeededWhenModeCoversApps() {
        assertTrue(isServiceNeeded(AutoOptimizeMode.ALL, emptySet(), CleanupThreshold.OFF))
        assertTrue(isServiceNeeded(AutoOptimizeMode.SIDELOADED, emptySet(), CleanupThreshold.OFF))
    }

    @Test fun serviceNeededWhenCleanupOnEvenIfNothingMaintained() {
        assertTrue(isServiceNeeded(AutoOptimizeMode.SELECTED, emptySet(), CleanupThreshold.MB_500))
        assertTrue(isServiceNeeded(AutoOptimizeMode.SELECTED, emptySet(), CleanupThreshold.GB_1))
        assertTrue(isServiceNeeded(AutoOptimizeMode.SELECTED, emptySet(), CleanupThreshold.GB_2))
    }

    @Test fun serviceNeededWhenBothArmedAndCleanupOn() {
        assertTrue(isServiceNeeded(AutoOptimizeMode.ALL, setOf("com.x.y"), CleanupThreshold.GB_1))
    }

    @Test fun serviceNeededWhileMeasuringEvenIfNothingElse() {
        assertTrue(isServiceNeeded(AutoOptimizeMode.SELECTED, emptySet(), CleanupThreshold.OFF, measuring = true))
    }

    @Test fun serviceNotNeededWhenNotMeasuring() {
        assertFalse(isServiceNeeded(AutoOptimizeMode.SELECTED, emptySet(), CleanupThreshold.OFF, measuring = false))
    }
}
