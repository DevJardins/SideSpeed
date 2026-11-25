package io.github.devjardins.sidespeed.ui.apps

import io.github.devjardins.sidespeed.testing.EnglishStrings
import io.github.devjardins.sidespeed.testing.english
import io.github.devjardins.sidespeed.ui.text.resolve
import org.junit.Assert.assertEquals
import org.junit.Test

class UpdateBannerTest {
    @Test
    fun updateBannerManual_english_is_unchanged() {
        assertEquals(
            "In Downloader, enter 3503400, or go to sidespeed.app/tv.",
            updateBannerManual().english(),
        )
    }

    @Test
    fun updateBannerManual_rtl_isolates_code_and_url() {
        assertEquals(
            "In Downloader, enter \u20663503400\u2069, or go to \u2066sidespeed.app/tv\u2069.",
            updateBannerManual().resolve(EnglishStrings, rtl = true),
        )
    }
}
