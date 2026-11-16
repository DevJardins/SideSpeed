package io.github.devjardins.sidespeed.companion.upkeep

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OverlayJoinTest {
    @Test fun onlyBecomingATrustedTailnetCountsAsJoining() {
        assertTrue(overlayJoined(null, "o1"))
        assertTrue(overlayJoined("o1", "o2"))
        assertFalse(overlayJoined("o1", "o1"), "peers changing on the same tailnet is not a join")
        assertFalse(overlayJoined("o1", null))
        assertFalse(overlayJoined(null, null))
    }
}
