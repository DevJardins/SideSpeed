package io.github.devjardins.sidespeed.companion.android

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
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

    /** Starts staging on a thread, waits until the copy is reading, then closes and checks it all ends promptly. */
    private fun assertCloseStopsStaging(name: String, stream: (reading: CountDownLatch) -> InputStream) {
        val cache = tmp.newFolder(name)
        val reading = CountDownLatch(1)
        val src = AndroidInstallSource("big.apks", -1L, { stream(reading) }, cache)
        val result = AtomicReference<Any?>("pending")
        val worker = thread { result.set(src.asZipFile()) }
        assertTrue("copy started", reading.await(5, TimeUnit.SECONDS))
        val started = System.nanoTime()
        src.close()
        val closeMs = (System.nanoTime() - started) / 1_000_000
        worker.join(5_000)
        assertFalse("staging thread ended", worker.isAlive)
        assertTrue("close returned in ${closeMs}ms", closeMs < 2_000)
        assertNull(result.get())
        assertEquals(0, File(cache, "install-staging").listFiles().orEmpty().size)
        assertNull(src.asZipFile())
    }

    @Test
    fun close_during_staging_returns_promptly_and_leaves_no_staging_file() {
        // A provider that keeps sending: the copy stops at its next read.
        assertCloseStopsStaging("slow") { reading ->
            object : InputStream() {
                override fun read(): Int = 0
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    reading.countDown()
                    Thread.sleep(2)
                    return len
                }
            }
        }
        // A provider stuck in a read: closing the stream unblocks it.
        assertCloseStopsStaging("stuck") { reading ->
            object : InputStream() {
                private val closed = CountDownLatch(1)
                override fun read(): Int = 0
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    reading.countDown()
                    closed.await()
                    throw IOException("stream closed")
                }
                override fun close() = closed.countDown()
            }
        }
    }

    @Test
    fun foreground_handoff_stops_only_a_foregrounded_service() {
        // The install ends before the service starts: the host stops nothing, and the late start stops itself.
        val early = ForegroundHandoff<String>()
        early.begin()
        assertNull(early.end())
        assertFalse(early.foregrounded("svc", 1))

        // The service is in the foreground first: the host gets it back with its start id to stop.
        val normal = ForegroundHandoff<String>()
        normal.begin()
        assertTrue(normal.foregrounded("svc", 3))
        assertEquals("svc", normal.current())
        assertEquals("svc" to 3, normal.end())
        assertNull(normal.current())
        assertNull(normal.end())

        // A destroyed service is never handed back.
        val gone = ForegroundHandoff<String>()
        gone.begin()
        gone.foregrounded("svc", 1)
        gone.destroyed("svc")
        assertNull(gone.end())

        // A second install after the first: the newer start id is the one to stop.
        val again = ForegroundHandoff<String>()
        again.begin(); again.foregrounded("svc", 1); again.end()
        again.begin(); assertTrue(again.foregrounded("svc", 2))
        assertEquals("svc" to 2, again.end())
    }

    @Test
    fun foreground_handoff_agrees_under_racing_end_and_start() {
        repeat(500) {
            val h = ForegroundHandoff<String>()
            h.begin()
            val keep = AtomicReference<Boolean>()
            val stopped = AtomicReference<Pair<String, Int>?>()
            val go = CountDownLatch(1)
            val a = thread { go.await(); keep.set(h.foregrounded("svc", 1)) }
            val b = thread { go.await(); stopped.set(h.end()) }
            go.countDown(); a.join(); b.join()
            // Exactly one side stops the service.
            assertTrue(keep.get() == (stopped.get() != null))
        }
    }
}
