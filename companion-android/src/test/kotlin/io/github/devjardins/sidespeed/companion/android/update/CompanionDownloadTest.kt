package io.github.devjardins.sidespeed.companion.android.update

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.nio.file.Files

class CompanionDownloadTest {
    private fun openedOf(
        bytes: ByteArray,
        finalUrl: String = "https://example.com/SideSpeed-Companion.apk",
        contentLength: Long = bytes.size.toLong(),
    ) = CompanionDownload.Opened(finalUrl, contentLength, ByteArrayInputStream(bytes))

    private fun tmpDir() = Files.createTempDirectory("companion-update-test").toFile()

    @Test fun fetchTextReturnsBody() {
        val download = CompanionDownload { openedOf("abc  SideSpeed-Companion.apk\n".toByteArray()) }
        val result = runBlocking { download.fetchText("https://example.com/SHA256SUMS.txt") }
        assertEquals("abc  SideSpeed-Companion.apk\n", result.getOrThrow())
    }

    @Test fun fetchTextRejectsNonHttpsFinalUrl() {
        val download = CompanionDownload { openedOf("x".toByteArray(), finalUrl = "http://example.com/f") }
        val result = runBlocking { download.fetchText("https://example.com/f") }
        assertTrue(result.isFailure)
    }

    @Test fun fetchTextEnforcesCap() {
        val download = CompanionDownload { openedOf("0123456789".toByteArray()) }
        val result = runBlocking { download.fetchText("https://example.com/f", maxBytes = 4) }
        assertTrue(result.isFailure)
    }

    @Test fun downloadToWritesFileAndReportsFullProgress() {
        val bytes = ByteArray(100) { it.toByte() }
        val download = CompanionDownload { openedOf(bytes) }
        val dir = tmpDir()
        try {
            val dest = dir.resolve("SideSpeed-Companion.apk")
            val seen = mutableListOf<Int>()
            val result = runBlocking { download.downloadTo("https://example.com/a.apk", dest, onProgress = seen::add) }
            assertTrue(result.isSuccess)
            assertEquals(bytes.toList(), dest.readBytes().toList())
            assertEquals(100, seen.last())
            assertTrue(seen == seen.sorted())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun downloadToRejectsNonHttpsFinalUrl() {
        val download = CompanionDownload { openedOf(ByteArray(10), finalUrl = "http://example.com/a.apk") }
        val dir = tmpDir()
        try {
            val result = runBlocking { download.downloadTo("https://example.com/a.apk", dir.resolve("a.apk")) }
            assertTrue(result.isFailure)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun downloadToEnforcesDeclaredLengthCap() {
        val download = CompanionDownload { openedOf(ByteArray(10)) }
        val dir = tmpDir()
        try {
            val result = runBlocking { download.downloadTo("https://example.com/a.apk", dir.resolve("a.apk"), maxBytes = 4) }
            assertTrue(result.isFailure)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun downloadToEnforcesStreamingCap() {
        // Lies about the length, then streams more than the cap.
        val download = CompanionDownload { openedOf(ByteArray(10), contentLength = 4) }
        val dir = tmpDir()
        try {
            val result = runBlocking { download.downloadTo("https://example.com/a.apk", dir.resolve("a.apk"), maxBytes = 8) }
            assertTrue(result.isFailure)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun sha256HexMatchesKnownVector() {
        val dir = tmpDir()
        try {
            val file = dir.resolve("abc.txt").apply { writeBytes("abc".toByteArray()) }
            assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", sha256Hex(file))
        } finally {
            dir.deleteRecursively()
        }
    }
}
