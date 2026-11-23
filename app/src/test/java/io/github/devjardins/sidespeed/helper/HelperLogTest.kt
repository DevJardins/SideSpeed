package io.github.devjardins.sidespeed.helper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.rules.Timeout
import java.io.File

class HelperLogTest {
    @get:Rule val tmp = TemporaryFolder()

    /** Every test runs on its own thread and fails after 10 s instead of hanging the build. */
    @get:Rule val timeout: Timeout = Timeout.seconds(10)

    @Test fun shortAsciiUnchanged() {
        assertEquals("sh -c id", HelperLog.sanitize("sh -c id"))
    }

    @Test fun emptyString() {
        assertEquals("", HelperLog.sanitize(""))
    }

    @Test fun exactlyEightyCharsUnchanged() {
        val request = "a".repeat(80)
        assertEquals(request, HelperLog.sanitize(request))
    }

    @Test fun longCutAtEightyWithByteCount() {
        val request = "a".repeat(600)
        assertEquals("a".repeat(80) + "…(600 bytes)", HelperLog.sanitize(request))
    }

    @Test fun controlCharsReplaced() {
        assertEquals("a?b?c", HelperLog.sanitize("a\tb\nc"))
    }

    @Test fun nonAsciiReplaced() {
        assertEquals("ping?", HelperLog.sanitize("ping\u00e9"))
        assertEquals("?", HelperLog.sanitize("\u007f"))
        assertEquals("?", HelperLog.sanitize("\u001f"))
        assertEquals(" ~", HelperLog.sanitize(" ~"))
    }

    @Test fun longNonAsciiCutReportsUtf8ByteCount() {
        val request = "é".repeat(100)
        assertEquals("?".repeat(80) + "…(200 bytes)", HelperLog.sanitize(request))
    }

    @Test fun ensurePrivateChmodsTo0600() {
        val calls = mutableListOf<Pair<File, Int>>()
        val chmod: (File, Int) -> Unit = { file, mode -> calls += file to mode }
        val log = tmp.root.resolve("sidespeed_helper.log")
        log.createNewFile()

        HelperLog.ensurePrivate(log, chmod)

        assertEquals(listOf(log to "600".toInt(8)), calls)
    }

    @Test fun ensurePrivateChmodsAfterRotate() {
        val calls = mutableListOf<Pair<File, Int>>()
        val chmod: (File, Int) -> Unit = { file, mode -> calls += file to mode }
        val log = tmp.root.resolve("sidespeed_helper.log")
        log.writeText("old\n")
        val rotated = tmp.root.resolve("sidespeed_helper.log.1")
        log.renameTo(rotated)

        HelperLog.ensurePrivate(rotated, chmod)

        assertEquals(listOf(rotated to "600".toInt(8)), calls)
        assertTrue(rotated.isFile)
    }

    @Test fun existingLogChmoddedOnFirstWriteOnly() {
        val log = tmp.root.resolve("sidespeed_helper.log")
        log.writeText("old\n")
        val rotated = tmp.root.resolve("sidespeed_helper.log.1")
        val calls = mutableListOf<Pair<File, Int>>()
        val privacy = HelperLog.LogPrivacy({ file, mode -> calls += file to mode })

        privacy.afterWrite(log, rotated, created = false, didRotate = false)

        assertEquals(listOf(log to "600".toInt(8)), calls)

        privacy.afterWrite(log, rotated, created = false, didRotate = false)

        assertEquals(1, calls.size)
    }

    @Test fun existingRotatedChmoddedOnFirstWrite() {
        val log = tmp.root.resolve("sidespeed_helper.log")
        log.writeText("old\n")
        val rotated = tmp.root.resolve("sidespeed_helper.log.1")
        rotated.writeText("older\n")
        val calls = mutableListOf<Pair<File, Int>>()
        val privacy = HelperLog.LogPrivacy({ file, mode -> calls += file to mode })

        privacy.afterWrite(log, rotated, created = false, didRotate = false)

        assertEquals(listOf(log to "600".toInt(8), rotated to "600".toInt(8)), calls)
    }

    @Test fun newFileChmoddedAfterCreation() {
        val log = tmp.root.resolve("sidespeed_helper.log")
        val rotated = tmp.root.resolve("sidespeed_helper.log.1")
        val calls = mutableListOf<Pair<File, Int>>()
        val privacy = HelperLog.LogPrivacy({ file, mode -> calls += file to mode })

        privacy.afterWrite(log, rotated, created = true, didRotate = false)

        assertEquals(listOf(log to "600".toInt(8)), calls)
    }

    @Test fun rotatedChmoddedAfterRotate() {
        val log = tmp.root.resolve("sidespeed_helper.log")
        val rotated = tmp.root.resolve("sidespeed_helper.log.1")
        val calls = mutableListOf<Pair<File, Int>>()
        val privacy = HelperLog.LogPrivacy({ file, mode -> calls += file to mode })

        privacy.afterWrite(log, rotated, created = true, didRotate = true)

        assertEquals(listOf(rotated to "600".toInt(8), log to "600".toInt(8)), calls)
    }
}
