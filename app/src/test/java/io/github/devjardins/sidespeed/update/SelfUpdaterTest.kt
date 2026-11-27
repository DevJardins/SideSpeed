package io.github.devjardins.sidespeed.update

import io.github.devjardins.sidespeed.helper.InstallOutcome
import io.github.devjardins.sidespeed.helper.SideSpeedPackages
import io.github.devjardins.sidespeed.testing.english
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.InputStream
import java.util.Collections

class SelfUpdaterTest {
    @get:Rule val tmp = TemporaryFolder()

    private val signer = setOf("e".repeat(64))
    private val apkFacts = ApkFacts("io.github.devjardins.sidespeed", 5, signer)

    private fun updater(
        sumsText: (sha: String) -> String = { sha -> "$sha  SideSpeed.apk\n" },
        apkBytes: ByteArray = "apk-bytes".toByteArray(),
        apkFacts: ApkFacts? = this.apkFacts,
        helperUp: Boolean = true,
        helperResult: InstallOutcome = InstallOutcome.Started,
        fallbackResult: InstallOutcome = InstallOutcome.Started,
        busyFlow: Flow<Boolean>? = null,
        open: ((url: String) -> UpdateDownload.Opened)? = null,
        throttleMs: Long = 0,
        helperCalls: MutableList<File> = mutableListOf(),
        fallbackCalls: MutableList<File> = mutableListOf(),
        scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob()),
        installed: () -> Pair<Long, Set<String>> = { 4L to signer },
        confirmTimeoutMs: Long = 200,
        confirmPollMs: Long = 5,
        fallbackProbe: ((File) -> Unit)? = null,
        ownPackage: String = SideSpeedPackages.RELEASE,
    ): SelfUpdater {
        val bytes = apkBytes
        val dl = UpdateDownload(open = open ?: { url ->
            if (url.endsWith("SHA256SUMS.txt")) {
                UpdateDownload.Opened(url, -1, sumsText(sha256HexBytes(bytes)).byteInputStream())
            } else {
                val body: InputStream =
                    if (throttleMs > 0) ThrottledInputStream(bytes, throttleMs) else bytes.inputStream()
                UpdateDownload.Opened(url, bytes.size.toLong(), body)
            }
        })
        return SelfUpdater(
            download = dl,
            readApkFacts = { apkFacts },
            installed = installed,
            helperUp = { helperUp },
            helperInstall = { apk ->
                synchronized(helperCalls) { helperCalls += apk }
                helperResult
            },
            fallbackInstall = { apk ->
                synchronized(fallbackCalls) { fallbackCalls += apk }
                fallbackProbe?.invoke(apk)
                fallbackResult
            },
            cacheDir = tmp.root,
            busy = busyFlow ?: flowOf(false),
            ownPackage = ownPackage,
            scope = scope,
            confirmTimeoutMs = confirmTimeoutMs,
            confirmPollMs = confirmPollMs,
        )
    }

    private fun sha256HexBytes(bytes: ByteArray): String {
        val f = tmp.newFile()
        f.writeBytes(bytes)
        return sha256Hex(f).also { f.delete() }
    }

    private suspend fun SelfUpdater.awaitState(predicate: (UpdateUiState) -> Boolean): UpdateUiState =
        withTimeout(5_000) { state.first(predicate) }

    /**
     * Installing is emitted before the install call is recorded, so awaiting the state alone
     * races the recording: wait for the recorded call instead.
     */
    private suspend fun awaitCalls(calls: List<File>, count: Int = 1) {
        withTimeout(5_000) {
            while (synchronized(calls) { calls.size } < count) delay(10)
        }
    }

    @Test fun busy_clears_to_available_when_job_ends() = runBlocking {
        val busy = MutableStateFlow(true)
        val u = updater(busyFlow = busy)
        u.start("v0.5.0")
        assertEquals(UpdateUiState.Busy, u.awaitState { it == UpdateUiState.Busy })
        busy.value = false
        u.awaitState { it == UpdateUiState.Available }
        u.start("v0.5.0")
        u.awaitState { it is UpdateUiState.Installing }
        Unit
    }

    @Test fun busy_true_shows_busy_and_start_is_noop() = runBlocking {
        val helperCalls = mutableListOf<File>()
        val u = updater(busyFlow = flowOf(true), helperCalls = helperCalls)
        u.start("v0.5.0")
        assertEquals(UpdateUiState.Busy, u.awaitState { it == UpdateUiState.Busy })
        assertTrue(helperCalls.isEmpty())
    }

    @Test fun happy_path_downloads_verifies_then_helper_install() = runBlocking {
        val content = ByteArray(64 * 1024) { it.toByte() }
        val seen = Collections.synchronizedList(mutableListOf<UpdateUiState>())
        val helperCalls = Collections.synchronizedList(mutableListOf<File>())
        val sequenceDone = kotlinx.coroutines.CompletableDeferred<Unit>()
        val u = updater(apkBytes = content, throttleMs = 10, helperCalls = helperCalls)
        val collect = launch {
            u.state.collect { s ->
                seen += s
                val i = seen.indexOfFirst { it is UpdateUiState.Downloading }
                if (i >= 0 && seen.drop(i).any { it is UpdateUiState.Installing }) {
                    if (!sequenceDone.isCompleted) sequenceDone.complete(Unit)
                }
            }
        }
        withTimeout(5_000) { u.state.first { it is UpdateUiState.Available } }
        u.start("v0.5.0")
        withTimeout(5_000) { sequenceDone.await() }
        collect.cancel()
        awaitCalls(helperCalls)
        assertEquals(1, helperCalls.size)
        assertTrue(helperCalls.single().name == "SideSpeed.apk")
    }

    @Test fun debug_self_updater_rejects_release_apk() = runBlocking {
        val helperCalls = Collections.synchronizedList(mutableListOf<File>())
        val fallbackCalls = Collections.synchronizedList(mutableListOf<File>())
        val u = updater(
            ownPackage = SideSpeedPackages.DEBUG,
            apkFacts = ApkFacts(SideSpeedPackages.RELEASE, 5, signer),
            helperCalls = helperCalls,
            fallbackCalls = fallbackCalls,
        )
        u.start("v0.5.0")
        val end = u.awaitState { it is UpdateUiState.Failed }
        assertEquals(
            "Wrong package: ${SideSpeedPackages.RELEASE}.",
            (end as UpdateUiState.Failed).reason.english(),
        )
        assertTrue(helperCalls.isEmpty())
        assertTrue(fallbackCalls.isEmpty())
    }

    @Test fun verify_rejected_goes_failed_and_deletes_file() = runBlocking {
        val u = updater(sumsText = { _ -> "${"f".repeat(64)}  SideSpeed.apk\n" })
        u.start("v0.5.0")
        val end = u.awaitState { it is UpdateUiState.Failed }
        assertEquals("Checksum mismatch.", (end as UpdateUiState.Failed).reason.english())
        assertFalse(File(tmp.root, "update/SideSpeed.apk").exists())
    }

    @Test fun helper_down_uses_fallback() = runBlocking {
        val helperCalls = Collections.synchronizedList(mutableListOf<File>())
        val fallbackCalls = Collections.synchronizedList(mutableListOf<File>())
        val u = updater(helperUp = false, helperCalls = helperCalls, fallbackCalls = fallbackCalls)
        u.start("v0.5.0")
        u.awaitState { it is UpdateUiState.Installing }
        awaitCalls(fallbackCalls)
        assertTrue(helperCalls.isEmpty())
        assertEquals(1, fallbackCalls.size)
    }

    @Test fun helper_down_after_ping_falls_back() = runBlocking {
        val helperCalls = Collections.synchronizedList(mutableListOf<File>())
        val fallbackCalls = Collections.synchronizedList(mutableListOf<File>())
        val u = updater(
            helperUp = true,
            helperResult = InstallOutcome.HelperDown,
            helperCalls = helperCalls,
            fallbackCalls = fallbackCalls,
        )
        u.start("v0.5.0")
        u.awaitState { it is UpdateUiState.Installing }
        awaitCalls(helperCalls)
        awaitCalls(fallbackCalls)
        assertEquals(1, helperCalls.size)
        assertEquals(1, fallbackCalls.size)
    }

    @Test fun impostor_disconnect_after_body_uses_fallback() = runBlocking {
        val helperCalls = Collections.synchronizedList(mutableListOf<File>())
        val fallbackCalls = Collections.synchronizedList(mutableListOf<File>())
        val fallbackSawApk = Collections.synchronizedList(mutableListOf<Boolean>())
        val u = updater(
            helperResult = InstallOutcome.Disconnected,
            fallbackResult = InstallOutcome.Started,
            helperCalls = helperCalls,
            fallbackCalls = fallbackCalls,
            // Record whether the APK still exists at the moment the fallback runs.
            fallbackProbe = { apk -> fallbackSawApk += apk.exists() },
        )
        u.start("v0.5.0")
        u.awaitState { it is UpdateUiState.Installing }
        awaitCalls(helperCalls)
        awaitCalls(fallbackCalls)
        assertEquals(1, helperCalls.size)
        assertEquals(1, fallbackCalls.size)
        assertEquals(listOf(true), fallbackSawApk.toList())
    }

    @Test fun end_zero_with_version_rise_is_success_without_fallback() = runBlocking {
        val fallbackCalls = Collections.synchronizedList(mutableListOf<File>())
        val reads = java.util.concurrent.atomic.AtomicInteger()
        val u = updater(
            helperResult = InstallOutcome.Started,
            fallbackCalls = fallbackCalls,
            // Baseline read returns 4; once the helper reports END:0 the install lands and reads return 5.
            installed = { (if (reads.incrementAndGet() <= 2) 4L else 5L) to signer },
        )
        u.start("v0.5.0")
        u.awaitState { it is UpdateUiState.Installing }
        // Give the confirmation poll time to observe the rise and clean up.
        withTimeout(5_000) {
            while (File(tmp.root, "update/SideSpeed.apk").exists()) delay(10)
        }
        assertTrue(fallbackCalls.isEmpty())
    }

    @Test fun end_zero_without_version_rise_is_failed() = runBlocking {
        val u = updater(helperResult = InstallOutcome.Started)
        u.start("v0.5.0")
        val end = u.awaitState { it is UpdateUiState.Failed }
        assertEquals("The update didn't install.", (end as UpdateUiState.Failed).reason.english())
        assertFalse(File(tmp.root, "update/SideSpeed.apk").exists())
    }

    @Test fun helper_failed_goes_failed_with_last_line() = runBlocking {
        val u = updater(helperResult = InstallOutcome.Failed(3, "boom"))
        u.start("v0.5.0")
        val end = u.awaitState { it is UpdateUiState.Failed }
        assertEquals("boom", (end as UpdateUiState.Failed).reason.english())
        assertFalse(File(tmp.root, "update/SideSpeed.apk").exists())
    }

    @Test fun failure_reasons_preserve_exact_english() = runBlocking {
        val badTag = updater()
        badTag.start("not a version")
        assertEquals("Not a valid release version.", (badTag.awaitState { it is UpdateUiState.Failed } as UpdateUiState.Failed).reason.english())

        val sumsFail = updater(open = { error("offline") })
        sumsFail.start("v0.5.0")
        assertEquals("Could not download the checksums.", (sumsFail.awaitState { it is UpdateUiState.Failed } as UpdateUiState.Failed).reason.english())

        val missingSum = updater(sumsText = { "" })
        missingSum.start("v0.5.0")
        assertEquals("No checksum found for SideSpeed.apk.", (missingSum.awaitState { it is UpdateUiState.Failed } as UpdateUiState.Failed).reason.english())

        val dlFail = updater(open = { url ->
            if (url.endsWith("SHA256SUMS.txt")) UpdateDownload.Opened(url, -1, "${"a".repeat(64)}  SideSpeed.apk\n".byteInputStream())
            else error("network lost")
        })
        dlFail.start("v0.5.0")
        assertEquals("Download failed.", (dlFail.awaitState { it is UpdateUiState.Failed } as UpdateUiState.Failed).reason.english())

        val fbDown = updater(helperUp = false, fallbackResult = InstallOutcome.HelperDown)
        fbDown.start("v0.5.0")
        assertEquals("Helper not running.", (fbDown.awaitState { it is UpdateUiState.Failed } as UpdateUiState.Failed).reason.english())

        val fbNull = updater(helperUp = false, fallbackResult = InstallOutcome.Failed(1, null))
        fbNull.start("v0.5.0")
        assertEquals("Install failed.", (fbNull.awaitState { it is UpdateUiState.Failed } as UpdateUiState.Failed).reason.english())
    }

    @Test fun cancel_during_download_returns_available_and_deletes() = runBlocking {
        val content = ByteArray(4 * 1024 * 1024) { it.toByte() }
        val u = updater(apkBytes = content, throttleMs = 10)
        u.start("v0.5.0")
        u.awaitState { it is UpdateUiState.Downloading }
        u.cancel()
        u.awaitState { it is UpdateUiState.Available }
        assertFalse(File(tmp.root, "update/SideSpeed.apk").exists())
        assertFalse(File(tmp.root, "update/SideSpeed.apk.tmp").exists())
    }

    @Test fun clear_stale_files_empties_update_dir() {
        val dir = File(tmp.root, "update").apply { mkdirs() }
        File(dir, "SideSpeed.apk").writeBytes("x".toByteArray())
        val u = updater()
        u.clearStaleFiles()
        val left = dir.listFiles()?.toList() ?: emptyList()
        assertTrue("expected empty update dir, found $left", left.isEmpty())
    }

    /** Finite body with a per-chunk delay, so state progression is observable mid-download. */
    private class ThrottledInputStream(private val data: ByteArray, private val chunkDelayMs: Long) : InputStream() {
        private var pos = 0
        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (pos >= data.size) return -1
            Thread.sleep(chunkDelayMs)
            val n = minOf(len, 4096, data.size - pos)
            data.copyInto(b, off, pos, pos + n)
            pos += n
            return n
        }
    }
}
