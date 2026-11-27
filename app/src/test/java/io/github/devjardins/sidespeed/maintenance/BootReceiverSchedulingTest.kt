package io.github.devjardins.sidespeed.maintenance

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class BootReceiverSchedulingTest {
    @Test fun enabledGoogleTvSchedulesBootAndDailyIndependentlyOfMaintenance() = runTest {
        var maint = 0
        var boot = 0
        var daily = 0
        handleBootCompleted(
            serviceNeeded = false,
            isFireOs = false,
            rebootRecoveryEnabled = true,
            startMaintenance = { maint++ },
            scheduleBoot = { boot++ },
            scheduleDaily = { daily++ },
        )
        assertEquals(0, maint)
        assertEquals(1, boot)
        assertEquals(1, daily)
    }

    @Test fun disabledGoogleTvSchedulesNeitherRecoveryJob() = runTest {
        var maint = 0
        var boot = 0
        var daily = 0
        handleBootCompleted(
            serviceNeeded = true,
            isFireOs = false,
            rebootRecoveryEnabled = false,
            startMaintenance = { maint++ },
            scheduleBoot = { boot++ },
            scheduleDaily = { daily++ },
        )
        assertEquals(1, maint)
        assertEquals(0, boot)
        assertEquals(0, daily)
    }

    @Test fun fireOsNeverSchedulesRecoveryJobs() = runTest {
        var maint = 0
        var boot = 0
        var daily = 0
        handleBootCompleted(
            serviceNeeded = true,
            isFireOs = true,
            rebootRecoveryEnabled = true,
            startMaintenance = { maint++ },
            scheduleBoot = { boot++ },
            scheduleDaily = { daily++ },
        )
        assertEquals(1, maint)
        assertEquals(0, boot)
        assertEquals(0, daily)
    }
}
