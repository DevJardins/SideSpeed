package io.github.devjardins.sidespeed.ui.tools

import io.github.devjardins.sidespeed.testing.EnglishStrings
import io.github.devjardins.sidespeed.testing.english
import io.github.devjardins.sidespeed.ui.text.resolve
import org.junit.Assert.assertEquals
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
}
