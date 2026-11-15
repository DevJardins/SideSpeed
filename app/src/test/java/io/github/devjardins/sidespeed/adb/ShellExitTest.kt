package io.github.devjardins.sidespeed.adb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ShellExitTest {
    @Test
    fun wrapAppendsExitMarker() {
        assertEquals(
            "pm compile -m speed -f a.b; echo __SIDESPEED_EXIT__:\$?",
            ShellExit.wrap("pm compile -m speed -f a.b"),
        )
    }

    @Test
    fun parseReadsCode() {
        assertEquals(0, ShellExit.parse("__SIDESPEED_EXIT__:0"))
    }

    @Test
    fun parseToleratesWhitespaceAndCarriageReturn() {
        assertEquals(255, ShellExit.parse("  __SIDESPEED_EXIT__:255\r"))
    }

    @Test
    fun parseIgnoresOtherLines() {
        assertNull(ShellExit.parse("Success"))
    }
}

class ShellExitSplitTest {
    @Test
    fun plainLineHasNoCode() {
        assertEquals("Success" to null, ShellExit.split("Success"))
    }

    @Test
    fun markerOnlyLineHasNoText() {
        assertEquals(null to 0, ShellExit.split("__SIDESPEED_EXIT__:0"))
    }

    @Test
    fun markerMidLineSplitsTextAndCode() {
        assertEquals("foo" to 3, ShellExit.split("foo__SIDESPEED_EXIT__:3\r"))
    }

    @Test
    fun unparsableMarkerIsKeptAsText() {
        assertEquals("x__SIDESPEED_EXIT__:abc" to null, ShellExit.split("x__SIDESPEED_EXIT__:abc"))
    }
}
