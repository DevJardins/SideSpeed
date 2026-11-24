package io.github.devjardins.sidespeed.tools

import io.github.devjardins.sidespeed.compile.CompileMode
import io.github.devjardins.sidespeed.testing.english
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ToolsStoreTest {
    @Test fun cleanupRecordRoundTrip() {
        val full = CleanupRecord(atMs = 1_764_000_000_000, freedBytes = 840_000_000, skipped = null)
        assertEquals(full, parseCleanupRecord(encodeCleanupRecord(full)))

        val skipped = CleanupRecord(atMs = 42, freedBytes = null, skipped = SkipReason.HELPER_DOWN.name)
        assertEquals(skipped, parseCleanupRecord(encodeCleanupRecord(skipped)))
    }

    @Test fun old_cleanup_record_shows_stored_reason() {
        val legacy = CleanupRecord(atMs = 42, freedBytes = null, skipped = "Helper not running")
        assertEquals("Helper not running", legacy.skipText()?.english())

        val enumRecord = CleanupRecord(atMs = 42, freedBytes = null, skipped = SkipReason.HELPER_DOWN.name)
        assertEquals("Helper not running", enumRecord.skipText()?.english())
        assertEquals(
            "Restart the helper to use this",
            CleanupRecord(atMs = 42, freedBytes = null, skipped = SkipReason.HELPER_OUTDATED.name).skipText()?.english(),
        )
        assertEquals(
            "Helper refused the command",
            CleanupRecord(atMs = 42, freedBytes = null, skipped = SkipReason.REFUSED.name).skipText()?.english(),
        )

        val custom = CleanupRecord(atMs = 42, freedBytes = null, skipped = "exit 1")
        assertEquals("exit 1", custom.skipText()?.english())
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

    @Test fun restorePendingRoundTrip() {
        val full = RestorePending(pkg = "com.x.y", mode = CompileMode.SPEED, versionCode = 12)
        assertEquals(full, parseRestorePending(encodeRestorePending(full)))
    }

    @Test fun restorePendingMalformedIsNull() {
        assertNull(parseRestorePending(null))
        assertNull(parseRestorePending(""))
        assertNull(parseRestorePending("com.x.y|speed"))
        assertNull(parseRestorePending("com.x.y|warp|12"))
    }

    @Test fun extrasDisabledRoundTrip() {
        val records = listOf(
            DisabledRecord("com.a", 100),
            DisabledRecord("com.b", 200),
        )
        assertEquals(records, parseExtrasDisabled(encodeExtrasDisabled(records)))
    }

    @Test fun cleanupThresholdBytes() {
        assertEquals(null, CleanupThreshold.OFF.bytes)
        assertEquals(500_000_000L, CleanupThreshold.MB_500.bytes)
        assertEquals(1_000_000_000L, CleanupThreshold.GB_1.bytes)
        assertEquals(2_000_000_000L, CleanupThreshold.GB_2.bytes)
    }
}
