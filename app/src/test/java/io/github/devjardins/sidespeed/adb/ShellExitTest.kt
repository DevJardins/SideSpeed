package io.github.devjardins.sidespeed.adb

import io.github.devjardins.sidespeed.helper.HelperLaunch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class ShellExitTest {
    @Test
    fun wrapAppendsExitMarker() {
        assertEquals(
            "pm compile -m speed -f a.b\necho __SIDESPEED_EXIT__:\$?",
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

/** The wrapped commands must parse in a POSIX shell; `cmd &; echo` is a syntax error, `cmd &<newline>echo` is not. */
class ShellExitSyntaxTest {
    private fun syntaxCheck(script: String): Int {
        assumeTrue("/bin/sh not available", File("/bin/sh").canExecute())
        val p = ProcessBuilder("/bin/sh", "-n", "-c", script).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        val code = p.waitFor()
        if (code != 0) println("sh -n output: $out")
        return code
    }

    @Test fun wrappedHelperLaunchIsValidShell() {
        assertEquals(0, syntaxCheck(ShellExit.wrap(HelperLaunch.command())))
    }

    @Test fun wrappedKillCommandIsValidShell() {
        assertEquals(0, syntaxCheck(ShellExit.wrap(ConnectionStrategy.KILL_COMMAND)))
    }

    @Test fun oldSemicolonJoinWasInvalid() {
        // Guards the test itself: the pre-fix form must be rejected by /bin/sh.
        assumeTrue(File("/bin/sh").canExecute())
        val code = syntaxCheck(HelperLaunch.command() + "; echo x")
        assertEquals(true, code != 0)
    }

    @Test fun markerArrivesOnItsOwnLine() {
        assertEquals(null to 0, ShellExit.split("__SIDESPEED_EXIT__:0"))
        assertEquals("Success" to null, ShellExit.split("Success"))
    }
}
