package io.github.devjardins.sidespeed.companion.android

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class InstallTransferServiceLogicTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun zipBytes(): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            z.putNextEntry(ZipEntry("base.apk")); z.write(ByteArray(64) { 1 }); z.closeEntry()
        }
        return out.toByteArray()
    }

    @Test
    fun clears_stale_staging_files_on_startup() {
        val cache = tmp.newFolder("cache")
        val staging = File(cache, "install-staging").apply { mkdirs() }
        File(staging, "old.zip").writeText("x")
        File(staging, "older.zip").writeText("y")
        clearStaleInstallStaging(cache)
        assertEquals(0, staging.listFiles()?.size ?: 0)
        // A missing directory is fine.
        clearStaleInstallStaging(tmp.newFolder("empty"))
    }

    @Test
    fun stages_split_bundle_and_deletes_on_close_while_streaming_single_apk_without_staging() {
        val cache = tmp.newFolder("cache2")
        val bytes = zipBytes()
        val bundle = AndroidInstallSource("app.apks", bytes.size.toLong(), { ByteArrayInputStream(bytes) }, cache)
        val zip = bundle.asZipFile()
        assertNotNull(zip)
        assertEquals(zip, bundle.asZipFile())
        val staged = File(cache, "install-staging").listFiles().orEmpty()
        assertEquals(1, staged.size)
        assertTrue(staged[0].name.endsWith(".zip"))
        bundle.close()
        assertEquals(0, File(cache, "install-staging").listFiles().orEmpty().size)

        // Unknown size (-1) on a single APK: streamed, never staged.
        var opens = 0
        val single = AndroidInstallSource("app.apk", -1L, { opens++; ByteArrayInputStream(bytes) }, cache)
        assertNull(single.sizeBytes)
        assertNull(single.asZipFile())
        assertEquals(bytes.size, single.openStream().use { it.readBytes().size })
        assertEquals(1, opens)
        assertFalse(File(cache, "install-staging").listFiles().orEmpty().isNotEmpty())
        single.close()
    }

    @Test
    fun staging_over_the_cap_is_abandoned_and_cleaned_up() {
        val cache = tmp.newFolder("cache3")
        val bytes = zipBytes()
        val src = AndroidInstallSource("big.zip", -1L, { ByteArrayInputStream(bytes) }, cache, maxBytes = 10)
        assertNull(src.asZipFile())
        assertEquals(0, File(cache, "install-staging").listFiles().orEmpty().size)
        src.close()
    }

    @Test
    fun notification_state_tracks_progress_commit_and_stop() {
        var now = 0L
        val m = InstallNotificationModel(throttleMs = 250) { now }
        assertNull(m.progress(1, 10, false)) // not started: ignored
        m.start("Living room", "Plex")
        now = 1_000
        val first = m.progress(5, 10, false)
        assertNotNull(first)
        assertEquals(50, first!!.percent)
        now = 1_100
        assertNull(m.progress(6, 10, false)) // throttled
        now = 1_100
        val commit = m.progress(10, 10, true) // phase change is never throttled
        assertTrue(commit!!.committing)
        assertEquals("Plex", commit.appName)
        // Unknown total: indeterminate.
        m.start("TV", "App")
        now = 5_000
        assertEquals(-1, m.progress(5, 0, false)!!.percent)
        m.end()
        assertNull(m.current())
        now = 9_000
        assertNull(m.progress(9, 10, false)) // late progress after end is ignored
    }
}
