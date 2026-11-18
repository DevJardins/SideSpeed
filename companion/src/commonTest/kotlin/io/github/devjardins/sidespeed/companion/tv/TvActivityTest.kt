package io.github.devjardins.sidespeed.companion.tv

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TvActivityTest {
    private val row =
        "Row: 0 v=1, state=compiling, pkg=com.example.app, label=Example%2C Inc, mode=speed, " +
            "started_ms=1731000000000, queue=2, last_pkg=com.example.other, last_label=Other, " +
            "last_result=ok, last_ms=1730999000000, self=optimized, version=0.2.1"

    @Test fun parsesCompilingRow() {
        val a = parseActivityRow(row)!!
        assertEquals(ActivityPhase.COMPILING, a.phase)
        assertEquals("com.example.app", a.pkg)
        assertEquals("Example, Inc", a.label)
        assertEquals("speed", a.mode)
        assertEquals(1731000000000L, a.startedMs)
        assertEquals(2, a.queue)
        assertEquals("com.example.other", a.lastPkg)
        assertEquals("Other", a.lastLabel)
        assertEquals(true, a.lastOk)
        assertEquals(1730999000000L, a.lastMs)
        assertEquals(SelfState.OPTIMIZED, a.self)
        assertEquals("0.2.1", a.version)
    }

    @Test fun labelsWithCommasAndEqualsRoundTrip() {
        val encoded = "A%2C B %3D C%2550%09tab"
        val out = "Row: 0 v=1, state=idle, pkg=, label=, mode=, started_ms=, queue=0, " +
            "last_pkg=, last_label=$encoded, last_result=, last_ms=, self=pending, version="
        val a = parseActivityRow(out)!!
        assertEquals(ActivityPhase.IDLE, a.phase)
        // %25 decodes first, so %2550 is a literal %50, not a comma.
        assertEquals("A, B = C%50\ttab", a.lastLabel)
    }

    @Test fun unknownVersionIsUnavailable() {
        assertNull(parseActivityRow(row.replace("v=1", "v=2")))
    }

    @Test fun missingColumnsAreUnavailable() {
        assertNull(parseActivityRow("Row: 0 v=1, state=idle"))
    }

    @Test fun blankOutputIsUnavailable() {
        assertNull(parseActivityRow(""))
        assertNull(parseActivityRow("   \n"))
    }

    @Test fun missingProviderNeedsUpdate() {
        val missing =
            "Error while accessing provider:io.github.devjardins.sidespeed.status\n" +
                "java.lang.IllegalStateException: Could not find provider: io.github.devjardins.sidespeed.status\n"
        val a = parseActivityRow(missing)!!
        assertEquals(ActivityPhase.NEEDS_UPDATE, a.phase)
    }

    @Test fun noResultFoundIsUnavailable() {
        assertNull(parseActivityRow("No result found."))
    }

    @Test fun interruptedAndWaitingHelperParse() {
        assertEquals(ActivityPhase.INTERRUPTED, parseActivityRow(row.replace("state=compiling", "state=interrupted"))!!.phase)
        assertEquals(ActivityPhase.WAITING_HELPER, parseActivityRow(row.replace("state=compiling", "state=waiting_helper"))!!.phase)
        assertEquals(ActivityPhase.IDLE, parseActivityRow(row.replace("state=compiling", "state=idle"))!!.phase)
    }

    @Test fun failedLastParses() {
        val a = parseActivityRow(row.replace("last_result=ok", "last_result=failed"))!!
        assertEquals(false, a.lastOk)
    }

    @Test fun selfCompilingDetectsOwnPackage() {
        val selfRow = row.replace("pkg=com.example.app", "pkg=$SIDESPEED_PACKAGE")
        val a = parseActivityRow(selfRow)!!
        assertTrue(a.selfCompiling)
        assertEquals(SelfState.OPTIMIZED, a.self)
    }

    @Test fun elapsedFormatsMinutesAndSeconds() {
        assertEquals("0:00", formatActivityElapsed(0L))
        assertEquals("0:07", formatActivityElapsed(7_000L))
        assertEquals("3:05", formatActivityElapsed(185_000L))
        assertEquals("12:30", formatActivityElapsed(750_000L))
    }

    @Test fun lastCaptionOmitsWhenNoLast() {
        val now = 1_731_000_000_000L
        assertNull(lastCaption("", 0L, now))
        assertNull(lastCaption("TizenTube", 0L, now))
        assertEquals("Last: TizenTube · 3 min ago", lastCaption("TizenTube", now - 180_000L, now))
        assertEquals("Last: TizenTube · just now", lastCaption("TizenTube", now - 10_000L, now))
        assertEquals("Last: TizenTube · 2 h ago", lastCaption("TizenTube", now - 7_200_000L, now))
    }
}
