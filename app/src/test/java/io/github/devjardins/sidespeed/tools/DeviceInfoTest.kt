package io.github.devjardins.sidespeed.tools

import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceInfoTest {
    @Test fun formatBytesMegabytes() {
        assertEquals("0 MB", formatBytes(0))
        assertEquals("1 MB", formatBytes(1_000_000))
        assertEquals("840 MB", formatBytes(840_000_000))
        assertEquals("999 MB", formatBytes(999_999_999))
    }

    @Test fun formatBytesGigabytes() {
        assertEquals("1.0 GB", formatBytes(1_000_000_000))
        assertEquals("1.2 GB", formatBytes(1_234_000_000))
        assertEquals("2.0 GB", formatBytes(2_000_000_000))
    }
}
