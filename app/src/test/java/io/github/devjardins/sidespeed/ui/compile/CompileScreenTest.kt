package io.github.devjardins.sidespeed.ui.compile

import org.junit.Assert.assertEquals
import org.junit.Test

class CompileScreenTest {
    @Test fun formatUpdateSize_whole_and_fractional() {
        assertEquals("12 MB", formatUpdateSize(12 * 1_048_576L))
        assertEquals("200 MB", formatUpdateSize(200 * 1_048_576L))
        assertEquals("12.5 MB", formatUpdateSize((12.5 * 1_048_576).toLong()))
        assertEquals("1 MB", formatUpdateSize(1_048_576L))
    }

    @Test fun updateHost_reads_host() {
        assertEquals(
            "f-droid.org",
            updateHost("https://f-droid.org/repo/com.example_3.apk"),
        )
        assertEquals(
            "example.com",
            updateHost("https://example.com:8443/a.apk"),
        )
    }
}
