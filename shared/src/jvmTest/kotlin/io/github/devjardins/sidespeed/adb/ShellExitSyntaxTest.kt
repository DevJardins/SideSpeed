package io.github.devjardins.sidespeed.adb

import io.github.devjardins.sidespeed.helper.HelperLaunch
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

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
        assertEquals(0, syntaxCheck(ShellExit.wrap(HelperLaunch.KILL_COMMAND)))
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
