package io.github.devjardins.sidespeed.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ToolsStoreTest {
    @Test fun cleanupRecordRoundTrip() {
        val full = CleanupRecord(atMs = 1_764_000_000_000, freedBytes = 840_000_000, skipped = null)
        assertEquals(full, parseCleanupRecord(encodeCleanupRecord(full)))

        val skipped = CleanupRecord(atMs = 42, freedBytes = null, skipped = "Helper not running")
        assertEquals(skipped, parseCleanupRecord(encodeCleanupRecord(skipped)))
    }

    @Test fun cleanupRecordMalformedIsNull() {
        assertNull(parseCleanupRecord(null))
        assertNull(parseCleanupRecord(""))
        assertNull(parseCleanupRecord("not-a-record"))
        assertNull(parseCleanupRecord("12|34"))
    }

    @Test fun launchResultRoundTrip() {
        val withBefore = LaunchResult(
            pkg = "com.x.y",
            versionCode = 12,
            lastUpdateTime = 345,
            beforeMs = 1900,
            afterMs = 1100,
            atMs = 678,
        )
        assertEquals(withBefore, parseLaunchResult("com.x.y", encodeLaunchResult(withBefore)))

        val withoutBefore = withBefore.copy(beforeMs = null)
        assertEquals(withoutBefore, parseLaunchResult("com.x.y", encodeLaunchResult(withoutBefore)))
    }

    @Test fun launchResultMalformedIsNull() {
        assertNull(parseLaunchResult("com.x.y", null))
        assertNull(parseLaunchResult("com.x.y", ""))
        assertNull(parseLaunchResult("com.x.y", "1|2|3"))
    }

    @Test fun animationOriginalsRoundTrip() {
        val full = AnimationOriginals(window = "1", transition = "0.5", animator = "1.5")
        assertEquals(full, parseAnimationOriginals(encodeAnimationOriginals(full)))

        val empty = AnimationOriginals(window = null, transition = null, animator = null)
        assertEquals(empty, parseAnimationOriginals(encodeAnimationOriginals(empty)))
    }

    @Test fun cleanupThresholdBytes() {
        assertEquals(null, CleanupThreshold.OFF.bytes)
        assertEquals(500_000_000L, CleanupThreshold.MB_500.bytes)
        assertEquals(1_000_000_000L, CleanupThreshold.GB_1.bytes)
        assertEquals(2_000_000_000L, CleanupThreshold.GB_2.bytes)
    }
}
