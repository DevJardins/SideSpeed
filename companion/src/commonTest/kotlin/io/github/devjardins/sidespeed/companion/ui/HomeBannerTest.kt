package io.github.devjardins.sidespeed.companion.ui

import io.github.devjardins.sidespeed.companion.testing.EnglishStrings
import io.github.devjardins.sidespeed.companion.testing.english
import io.github.devjardins.sidespeed.companion.ui.text.resolve
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HomeBannerTest {
    @Test fun lanFirstThenATrustedConnectedTailnetElseNothing() {
        val home = requireNotNull(trustBanner(CompanionUiState(trustedNetworkName = "Home")))
        assertEquals("Trusted Network: Home ✓", home.english())
        assertEquals("Trusted Network: \u2066Home\u2069 ✓", home.resolve(EnglishStrings, rtl = true))

        val tailnet = requireNotNull(
            trustBanner(CompanionUiState(tailnet = TailnetState("me@example.com", trusted = true, ready = true))),
        )
        assertEquals("Trusted tailnet: me@example.com ✓", tailnet.english())
        assertEquals("Trusted tailnet: \u2066me@example.com\u2069 ✓", tailnet.resolve(EnglishStrings, rtl = true))

        assertNull(trustBanner(CompanionUiState(tailnet = TailnetState("me@example.com", trusted = false, ready = true))))
        assertNull(trustBanner(CompanionUiState()))
    }
}
