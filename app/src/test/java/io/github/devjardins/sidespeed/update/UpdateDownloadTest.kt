package io.github.devjardins.sidespeed.update

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream

class UpdateDownloadTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun asset_url_for_valid_tag() {
        assertEquals(
            "https://github.com/DevJardins/SideSpeed/releases/download/v0.5.0/SideSpeed.apk",
            releaseAssetUrl("v0.5.0", "SideSpeed.apk"),
        )
    }

    @Test fun asset_url_rejects_bad_tag() {
        assertEquals(null, releaseAssetUrl("v0.5.0/../x", "SideSpeed.apk"))
        assertEquals(null, releaseAssetUrl("", "SideSpeed.apk"))
        assertEquals(null, releaseAssetUrl("latest", "SideSpeed.apk"))
    }

    private fun bytes(n: Int): ByteArray = ByteArray(n) { (it % 251).toByte() }

    @Test fun download_writes_file_and_reports_progress_to_100() = runBlocking {
        val data = bytes(100_000)
        val dl = UpdateDownload(open = {
            UpdateDownload.Opened("https://example.com/SideSpeed.apk", data.size.toLong(), ByteArrayInputStream(data))
        })
        val dest = tmp.root.resolve("SideSpeed.apk")
        val progress = mutableListOf<Int>()
        val result = dl.downloadTo("https://example.com/SideSpeed.apk", dest, onProgress = { progress += it })
        assertTrue(result.isSuccess)
        assertEquals(data.toList(), dest.readBytes().toList())
        assertFalse(tmp.root.resolve("SideSpeed.apk.tmp").exists())
        assertTrue(progress.isNotEmpty())
        assertEquals(100, progress.last())
        assertTrue(progress.zipWithNext().all { (a, b) -> a <= b })
    }

    @Test fun download_over_cap_fails_and_deletes_partial() = runBlocking {
        val data = bytes(100)
        val dl = UpdateDownload(open = {
            UpdateDownload.Opened("https://example.com/SideSpeed.apk", data.size.toLong(), ByteArrayInputStream(data))
        })
        val dest = tmp.root.resolve("SideSpeed.apk")
        val result = dl.downloadTo("https://example.com/SideSpeed.apk", dest, maxBytes = 10)
        assertTrue(result.isFailure)
        assertFalse("dest must not exist", dest.exists())
        assertFalse("partial must not exist", tmp.root.resolve("SideSpeed.apk.tmp").exists())
    }

    @Test fun download_body_shorter_than_content_length_fails_and_deletes() = runBlocking {
        val data = bytes(10)
        val dl = UpdateDownload(open = {
            UpdateDownload.Opened("https://example.com/SideSpeed.apk", 100L, ByteArrayInputStream(data))
        })
        val dest = tmp.root.resolve("SideSpeed.apk")
        assertTrue(dl.downloadTo("https://example.com/SideSpeed.apk", dest).isFailure)
        assertFalse(dest.exists())
    }

    @Test fun download_non_https_final_url_fails() = runBlocking {
        val dl = UpdateDownload(open = {
            UpdateDownload.Opened("http://example.com/SideSpeed.apk", 10L, ByteArrayInputStream(bytes(10)))
        })
        val dest = tmp.root.resolve("SideSpeed.apk")
        assertTrue(dl.downloadTo("http://example.com/SideSpeed.apk", dest).isFailure)
        assertFalse(dest.exists())
    }

    @Test fun fetch_text_reads_body() = runBlocking {
        val dl = UpdateDownload(open = {
            UpdateDownload.Opened("https://example.com/SHA256SUMS.txt", 3L, ByteArrayInputStream("abc".toByteArray()))
        })
        assertEquals("abc", dl.fetchText("https://example.com/SHA256SUMS.txt").getOrThrow())
    }

    @Test fun fetch_text_over_cap_fails() = runBlocking {
        val dl = UpdateDownload(open = {
            UpdateDownload.Opened("https://example.com/x", 100L, ByteArrayInputStream(bytes(100)))
        })
        try {
            dl.fetchText("https://example.com/x", maxBytes = 10).getOrThrow()
            fail("expected failure")
        } catch (_: IllegalStateException) {
        }
    }
}
