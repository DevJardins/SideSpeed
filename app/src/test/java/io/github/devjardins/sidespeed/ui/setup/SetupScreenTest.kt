package io.github.devjardins.sidespeed.ui.setup

import io.github.devjardins.sidespeed.testing.EnglishStrings
import io.github.devjardins.sidespeed.testing.english
import io.github.devjardins.sidespeed.ui.text.resolve
import org.junit.Assert.assertEquals
import org.junit.Test

class SetupScreenTest {
    @Test
    fun setupFireOsCompanionHint_english_is_unchanged() {
        assertEquals(
            "Open SideSpeed Companion on your phone or computer. It starts the helper for you. Get it from github.com/DevJardins/SideSpeed/releases",
            setupFireOsCompanionHint().english(),
        )
    }

    @Test
    fun setupFireOsCompanionHint_rtl_isolates_url() {
        assertEquals(
            "Open SideSpeed Companion on your phone or computer. It starts the helper for you. Get it from \u2066github.com/DevJardins/SideSpeed/releases\u2069",
            setupFireOsCompanionHint().resolve(EnglishStrings, rtl = true),
        )
    }
}
