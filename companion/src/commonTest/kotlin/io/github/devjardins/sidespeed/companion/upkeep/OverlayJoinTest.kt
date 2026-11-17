package io.github.devjardins.sidespeed.companion.upkeep

import io.github.devjardins.sidespeed.companion.net.NetworkFingerprint
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OverlayJoinTest {
    private val home = NetworkFingerprint("192.168.5.1", "192.168.5.0/24", setOf("192.168.5.1"))
    private val cafe = NetworkFingerprint("10.0.0.1", "10.0.0.0/24", setOf("10.0.0.1"))

    @Test fun onlyBecomingATrustedTailnetCountsAsJoining() {
        assertTrue(overlayJoined(null, "o1"))
        assertTrue(overlayJoined("o1", "o2"))
        assertFalse(overlayJoined("o1", "o1"), "peers changing on the same tailnet is not a join")
        assertFalse(overlayJoined("o1", null))
        assertFalse(overlayJoined(null, null))
    }

    @Test fun aTailnetAlreadyTrustedAtStartIsNotAJoin() = runTest {
        val poll = JoinPoll(home, "o1")
        assertFalse(poll.tick(home, "o1") { error("fingerprint unchanged") })
    }

    @Test fun joiningATrustedTailnetAfterStartIsAJoin() = runTest {
        val poll = JoinPoll(home, null)
        assertTrue(poll.tick(home, "o1") { false })
    }

    @Test fun aLanChangeToATrustedNetworkIsAJoin() = runTest {
        val poll = JoinPoll(home, null)
        assertTrue(poll.tick(cafe, null) { fp -> fp == cafe })
    }

    @Test fun leavingAndRejoiningATailnetIsAJoinAgain() = runTest {
        val poll = JoinPoll(home, "o1")
        assertFalse(poll.tick(home, "o1") { false })
        assertFalse(poll.tick(home, null) { false })
        assertTrue(poll.tick(home, "o1") { false })
    }
}
