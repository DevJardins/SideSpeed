package io.github.devjardins.sidespeed.companion.net

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OverlayTrustMatcherTest {
    private fun ip(s: String) = Ipv4.parse(s)!!
    private val trusted = TrustedOverlay(id = "overlay-1", name = "Home", tailnet = "me@example.com", selfId = "nSELF")
    private fun snap(
        tailnet: String = "me@example.com",
        selfId: String = "nSELF",
        running: Boolean = true,
        tun: Boolean = true,
    ) = OverlaySnapshot(tailnet, selfId, listOf(ip("100.100.1.1")), running, tun, emptyList())

    @Test fun matchesOnTailnetAndSelfIdWhileRunningWithATunDevice() {
        assertTrue(OverlayTrustMatcher.matches(snap(), trusted))
    }

    @Test fun everyFieldMismatchIsClosed() {
        assertFalse(OverlayTrustMatcher.matches(snap(tailnet = "other@example.com"), trusted))
        assertFalse(OverlayTrustMatcher.matches(snap(selfId = "nOTHER"), trusted))
        assertFalse(OverlayTrustMatcher.matches(snap(running = false), trusted))
        assertFalse(OverlayTrustMatcher.matches(snap(tun = false), trusted), "userspace mode can't reach peers")
        assertFalse(OverlayTrustMatcher.matches(null, trusted))
    }

    @Test fun blankIdentityNeverMatches() {
        val blank = TrustedOverlay("overlay-2", "x", tailnet = "", selfId = "")
        assertFalse(OverlayTrustMatcher.matches(snap(tailnet = "", selfId = ""), blank))
    }

    @Test fun currentTrustedPicksTheMatchingOverlayOrNull() {
        val other = TrustedOverlay("overlay-9", "Work", "corp.example", "nWORK")
        assertEquals(trusted, OverlayTrustMatcher.currentTrusted(snap(), listOf(other, trusted)))
        assertNull(OverlayTrustMatcher.currentTrusted(snap(tailnet = "nope"), listOf(other, trusted)))
        assertNull(OverlayTrustMatcher.currentTrusted(null, listOf(trusted)))
    }
}
