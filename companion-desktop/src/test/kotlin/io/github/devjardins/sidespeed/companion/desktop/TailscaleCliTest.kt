package io.github.devjardins.sidespeed.companion.desktop

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class TailscaleCliTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun script(body: String): String {
        assumeTrue(File("/bin/sh").canExecute())
        val f = tmp.newFile("tailscale")
        f.writeText("#!/bin/sh\n$body\n")
        f.setExecutable(true)
        return f.absolutePath
    }

    @Test fun returnsTheStatusJsonTheCliPrints() = runBlocking {
        val cli = TailscaleCli(listOf(script("""echo '{"Peer":{}}'""")), timeoutSeconds = 5)
        assertEquals("""{"Peer":{}}""", cli.statusJson()?.trim())
    }

    @Test fun aCliThatNeverFinishesIsGivenUpOnAtTheTimeout() = runBlocking {
        // Holds stdout open and sleeps: reading to the end before waiting would block here forever.
        val cli = TailscaleCli(listOf(script("sleep 30")), timeoutSeconds = 1)
        val started = System.nanoTime()
        assertNull(cli.statusJson())
        val seconds = (System.nanoTime() - started) / 1_000_000_000
        assertTrue("took ${seconds}s", seconds < 10)
    }

    @Test fun aFailingCliOrMissingBinaryGivesNull() = runBlocking {
        assertNull(TailscaleCli(listOf(script("exit 1")), timeoutSeconds = 5).statusJson())
        assertNull(TailscaleCli(listOf("/nonexistent/tailscale"), timeoutSeconds = 5).statusJson())
    }
}
