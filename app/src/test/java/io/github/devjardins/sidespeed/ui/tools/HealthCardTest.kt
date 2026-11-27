package io.github.devjardins.sidespeed.ui.tools

import io.github.devjardins.sidespeed.maintenance.RecoveryOutcome
import io.github.devjardins.sidespeed.maintenance.RecoveryReason
import io.github.devjardins.sidespeed.testing.EnglishStrings
import io.github.devjardins.sidespeed.testing.english
import io.github.devjardins.sidespeed.ui.text.resolve
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HealthCardTest {
    @Test fun deviceNameWinsWhenPresent() {
        assertEquals("Living Room TV", deviceLabel("Living Room TV", "Amazon", "AFTMM"))
    }

    @Test fun blankDeviceNameFallsBackToManufacturerAndModel() {
        assertEquals("Amazon AFTMM", deviceLabel(null, "Amazon", "AFTMM"))
        assertEquals("Amazon AFTMM", deviceLabel("  ", "Amazon", "AFTMM"))
    }

    @Test fun modelStartingWithManufacturerIsNotDoubled() {
        assertEquals("Amazon Fire TV Stick 4K", deviceLabel(null, "Amazon", "Amazon Fire TV Stick 4K"))
        assertEquals("onn. 4K Streaming Box", deviceLabel(null, "onn.", "onn. 4K Streaming Box"))
    }

    @Test fun manufacturerIsCapitalised() {
        assertEquals("Amazon AFTMM", deviceLabel(null, "amazon", "AFTMM"))
    }

    @Test fun deviceLineReadsDeviceThenAndroidVersion() {
        assertEquals("Living Room TV · Android 14", deviceLine("Living Room TV", "Google", "x", "14").english())
    }

    @Test fun deviceLineKeepsEachPartWholeRightToLeft() {
        assertEquals(
            "\u2066sdk_google_atv64_arm64\u2069 · \u2066Android 14\u2069",
            deviceLine("sdk_google_atv64_arm64", "Google", "x", "14").resolve(EnglishStrings, rtl = true),
        )
    }

    @Test fun recoveryHealthLineFormatsEveryOutcome() {
        assertNull(recoveryHealthLine(enabled = false, lastRecovery = RecoveryOutcome.StartedAgain(1L)))
        assertNull(recoveryHealthLine(enabled = true, lastRecovery = null))
        assertEquals(
            "After the last restart: Started again",
            recoveryHealthLine(true, RecoveryOutcome.StartedAgain(1L))!!.english(),
        )
        assertEquals(
            "After the last restart: Couldn't start (new Wi-Fi network). Open SideSpeed to allow it.",
            recoveryHealthLine(true, RecoveryOutcome.Failed(RecoveryReason.NEW_NETWORK, 1L))!!.english(),
        )
        assertEquals(
            "After the last restart: Couldn't start (location is off)",
            recoveryHealthLine(true, RecoveryOutcome.Failed(RecoveryReason.LOCATION_OFF, 1L))!!.english(),
        )
        assertEquals(
            "After the last restart: Couldn't start (the TV asked for permission)",
            recoveryHealthLine(true, RecoveryOutcome.Failed(RecoveryReason.PROMPTED, 1L))!!.english(),
        )
        for (reason in listOf(
            RecoveryReason.WRITE_REFUSED,
            RecoveryReason.ADB_FAILED,
            RecoveryReason.NO_PERMISSION,
            RecoveryReason.NOT_ON_WIFI,
        )) {
            assertEquals(
                "After the last restart: Couldn't start",
                recoveryHealthLine(true, RecoveryOutcome.Failed(reason, 1L))!!.english(),
            )
        }
    }

    @Test fun dailyRefreshHealthLineOnlyShowsWhenEnabledAndFailed() {
        assertNull(dailyRefreshHealthLine(enabled = false, failed = true))
        assertNull(dailyRefreshHealthLine(enabled = true, failed = false))
        assertEquals(
            "SideSpeed couldn't reach wireless debugging today.",
            dailyRefreshHealthLine(enabled = true, failed = true)!!.english(),
        )
    }
}
