package io.github.devjardins.sidespeed.ui.compile

import io.github.devjardins.sidespeed.testing.EnglishStrings
import io.github.devjardins.sidespeed.testing.english
import io.github.devjardins.sidespeed.ui.text.resolve
import org.junit.Assert.assertEquals
import org.junit.Test

class CompileScreenTest {
    @Test fun formatUpdateSize_whole_and_fractional() {
        assertEquals("12 MB", formatUpdateSize(12 * 1_048_576L).english())
        assertEquals("200 MB", formatUpdateSize(200 * 1_048_576L).english())
        assertEquals("12.5 MB", formatUpdateSize((12.5 * 1_048_576).toLong()).english())
        assertEquals("1 MB", formatUpdateSize(1_048_576L).english())
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

    @Test fun updateDoneText_english_and_rtl() {
        assertEquals(
            "Updated to 1.2.0-beta.2.",
            updateDoneText("1.2.0-beta.2").english(),
        )
        assertEquals(
            "Updated to \u20661.2.0-beta.2\u2069.",
            updateDoneText("1.2.0-beta.2").resolve(EnglishStrings, rtl = true),
        )
    }

    @Test fun updateSummaryText_english_and_rtl() {
        val url = "https://f-droid.org/repo/com.example_3.apk"
        assertEquals(
            "Downloads 12 MB from f-droid.org. SideSpeed checks it is a real update of the app you have, then installs it. Your data in the app stays.",
            updateSummaryText(12 * 1_048_576L, url).english(),
        )
        assertEquals(
            "Downloads \u206612\u2069 MB from \u2066f-droid.org\u2069. SideSpeed checks it is a real update of the app you have, then installs it. Your data in the app stays.",
            updateSummaryText(12 * 1_048_576L, url).resolve(EnglishStrings, rtl = true),
        )
    }
}
