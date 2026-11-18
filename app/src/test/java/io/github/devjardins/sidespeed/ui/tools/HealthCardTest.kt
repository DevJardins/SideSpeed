package io.github.devjardins.sidespeed.ui.tools

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
}
