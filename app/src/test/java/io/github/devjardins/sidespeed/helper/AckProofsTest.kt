package io.github.devjardins.sidespeed.helper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.rules.Timeout
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class AckProofsTest {
    @get:Rule val tmp = TemporaryFolder()

    /** Every test runs on its own thread and fails after 10 s instead of hanging the build. */
    @get:Rule val timeout: Timeout = Timeout.seconds(10)

    private fun dir() = tmp.root.resolve("acks")

    @Test fun writeCreatesPerNonceFileHoldingTheNonce() {
        val d = dir()
        AckProofs.write(d, "abcdefghijklmnop1234") { _, _ -> }
        assertEquals("abcdefghijklmnop1234", d.resolve("abcdefghijklmnop1234").readText().trim())
    }

    @Test fun matchesOnlyItsOwnNonceFile() {
        val d = dir()
        AckProofs.write(d, "abcdefghijklmnop1234") { _, _ -> }
        assertTrue(AckProofs.matches(d, "abcdefghijklmnop1234"))
        assertFalse(AckProofs.matches(d, "othernonceotherno1234567890"))
        assertFalse(AckProofs.matches(tmp.root.resolve("missing"), "abcdefghijklmnop1234"))
    }

    @Test fun wrongContentDoesNotMatch() {
        val d = dir()
        d.mkdirs()
        d.resolve("abcdefghijklmnop1234").writeText("someoneElsesNonce0123456789abcde\n")
        assertFalse(AckProofs.matches(d, "abcdefghijklmnop1234"))
    }

    @Test fun badNonceIsRefused() {
        val d = dir()
        listOf("short", "", "../escape", "a".repeat(65), "with space here 1234").forEach {
            try {
                AckProofs.write(d, it) { _, _ -> }
                fail("expected rejection of '$it'")
            } catch (_: IllegalArgumentException) {
            }
        }
        assertFalse(d.exists())
    }

    @Test fun concurrentWritesDoNotOverwriteEachOther() {
        val d = dir()
        val nonces = (0 until 16).map { "nonce%012dABCD".format(it) }
        val start = CountDownLatch(1)
        val done = CountDownLatch(nonces.size)
        val errors = Collections.synchronizedList(mutableListOf<Throwable>())
        nonces.forEach { nonce ->
            thread(isDaemon = true) {
                try {
                    start.await(5, TimeUnit.SECONDS)
                    AckProofs.write(d, nonce) { _, _ -> }
                } catch (t: Throwable) {
                    errors += t
                } finally {
                    done.countDown()
                }
            }
        }
        start.countDown()
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertTrue(errors.toString(), errors.isEmpty())
        nonces.forEach { assertTrue(it, AckProofs.matches(d, it)) }
    }

    @Test fun staleFilesAreCleanedUpOnWrite() {
        val d = dir()
        d.mkdirs()
        repeat(70) { d.resolve("fresh%012dABCD".format(it)).writeText("x") }
        val stale = d.resolve("stalenonce12345678")
        stale.writeText("x")
        stale.setLastModified(System.currentTimeMillis() - 120_000)
        AckProofs.write(d, "abcdefghijklmnop1234") { _, _ -> }
        assertFalse("stale file must go", stale.exists())
        val kept = d.listFiles()!!.map { it.name }
        assertTrue(kept.contains("abcdefghijklmnop1234"))
        assertTrue("directory stays bounded: ${kept.size}", kept.size <= AckProofs.MAX_FILES)
    }
}
