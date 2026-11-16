package io.github.devjardins.sidespeed.companion.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HomeBannerTest {
    @Test fun lanFirstThenATrustedConnectedTailnetElseNothing() {
        assertEquals("Trusted Network: Home ✓", trustBanner(CompanionUiState(trustedNetworkName = "Home")))
        assertEquals(
            "Trusted tailnet: me@example.com ✓",
            trustBanner(CompanionUiState(tailnet = TailnetState("me@example.com", trusted = true, ready = true))),
        )
        assertNull(trustBanner(CompanionUiState(tailnet = TailnetState("me@example.com", trusted = false, ready = true))))
        assertNull(trustBanner(CompanionUiState()))
    }
}
