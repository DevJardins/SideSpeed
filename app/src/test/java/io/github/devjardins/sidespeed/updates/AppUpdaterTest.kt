package io.github.devjardins.sidespeed.updates

import io.github.devjardins.sidespeed.helper.InstallOutcome
import io.github.devjardins.sidespeed.testing.english
import io.github.devjardins.sidespeed.update.ApkFacts
import io.github.devjardins.sidespeed.update.UpdateDownload
import io.github.devjardins.sidespeed.update.sha256Hex
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.InputStream
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

class AppUpdaterTest {
    @get:Rule val tmp = TemporaryFolder()

    private val pkg = "com.nuvio.tv"
    private val signer = setOf("e".repeat(64))

    private fun testVariant(
        bytes: ByteArray,
        version: Long = 3,
        signerSet: Set<String> = signer,
    ): ApkVariant {
        val f = tmp.newFile()
        f.writeBytes(bytes)
        val sha = sha256Hex(f).also { f.delete() }
        return ApkVariant(
            url = "https://example.com/a.apk",
            sha256 = sha,
            size = bytes.size.toLong(),
            versionCode = version,
            abis = listOf("arm64-v8a"),
            minSdk = 28,
            signer = signerSet,
        )
    }

    private fun updater(
        apkBytes: ByteArray = "apk-bytes".toByteArray(),
        variant: ApkVariant? = null,
        apkFacts: ApkFacts? = null,
        freeBytes: Long = Long.MAX_VALUE,
        helperUp: Boolean = true,
        helperResult: InstallOutcome = InstallOutcome.Started,
        fallbackResult: InstallOutcome = InstallOutcome.Started,
        installed: (String) -> Pair<Long, Set<String>> = { 2L to signer },
        confirmTimeoutMs: Long = 200,
        confirmPollMs: Long = 5,
        disconnectTimeoutMs: Long = 200,
        throttleMs: Long = 0,
        helperCalls: MutableList<String> = Collections.synchronizedList(mutableListOf()),
        fallbackCalls: MutableList<File> = Collections.synchronizedList(mutableListOf()),
        scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob()),
    ): AppUpdater {
        val bytes = apkBytes
        val v = variant ?: testVariant(bytes)
        val facts = apkFacts ?: ApkFacts(pkg, v.versionCode, v.signer)
        val dl = UpdateDownload(open = { url ->
            val body: InputStream =
                if (throttleMs > 0) ThrottledInputStream(bytes, throttleMs) else bytes.inputStream()
            UpdateDownload.Opened(url, bytes.size.toLong(), body)
        })
        return AppUpdater(
            download = dl,
            readApkFacts = { facts },
            installed = installed,
            helperUp = { helperUp },
            helperInstall = { p, _ ->
                helperCalls += p
                helperResult
            },
            fallbackInstall = { apk ->
                fallbackCalls += apk
                fallbackResult
            },
            cacheDir = tmp.root,
            freeSpace = { freeBytes },
            scope = scope,
            confirmTimeoutMs = confirmTimeoutMs,
            confirmPollMs = confirmPollMs,
            disconnectTimeoutMs = disconnectTimeoutMs,
        )
    }

    private suspend fun AppUpdater.awaitState(predicate: (AppUpdateUiState) -> Boolean): AppUpdateUiState =
        withTimeout(5_000) { state.first(predicate) }

    private suspend fun awaitCalls(calls: List<*>, count: Int = 1) {
        withTimeout(5_000) {
            while (synchronized(calls) { calls.size } < count) delay(10)
        }
    }

    private fun updateFile() = File(tmp.root, "app-updates/$pkg.apk")

    @Test fun happy_path_is_done_and_deletes_file() = runBlocking {
        val bytes = "apk-bytes".toByteArray()
        val v = testVariant(bytes)
        val reads = AtomicInteger()
        val u = updater(apkBytes = bytes, variant = v, installed = { (if (reads.incrementAndGet() <= 2) 2L else 3L) to signer })
        u.start(pkg, v, "2")
        val end = u.awaitState { it is AppUpdateUiState.Done }
        assertEquals(AppUpdateUiState.Done("2"), end)
        assertFalse(updateFile().exists())
    }

    @Test fun checksum_fail_is_failed_and_deletes_file() = runBlocking {
        val bytes = "apk-bytes".toByteArray()
        val v = testVariant(bytes).copy(sha256 = "f".repeat(64))
        val helperCalls = Collections.synchronizedList(mutableListOf<String>())
        val u = updater(apkBytes = bytes, variant = v, helperCalls = helperCalls)
        u.start(pkg, v, "2")
        val end = u.awaitState { it is AppUpdateUiState.Failed }
        assertEquals("Checksum mismatch.", (end as AppUpdateUiState.Failed).reason.english())
        assertTrue(helperCalls.isEmpty())
        assertFalse(updateFile().exists())
    }

    @Test fun signer_fail_is_failed() = runBlocking {
        val bytes = "apk-bytes".toByteArray()
        val v = testVariant(bytes)
        val facts = ApkFacts(pkg, v.versionCode, setOf("0".repeat(64)))
        val u = updater(apkBytes = bytes, variant = v, apkFacts = facts)
        u.start(pkg, v, "2")
        val end = u.awaitState { it is AppUpdateUiState.Failed }
        val reason = (end as AppUpdateUiState.Failed).reason.english()
        assertTrue(reason, reason.contains("Signer"))
        assertFalse(updateFile().exists())
    }

    @Test fun helper_down_goes_to_fallback() = runBlocking {
        val bytes = "apk-bytes".toByteArray()
        val v = testVariant(bytes)
        val helperCalls = Collections.synchronizedList(mutableListOf<String>())
        val fallbackCalls = Collections.synchronizedList(mutableListOf<File>())
        val u = updater(apkBytes = bytes, variant = v, helperUp = false, helperCalls = helperCalls, fallbackCalls = fallbackCalls)
        u.start(pkg, v, "2")
        u.awaitState { it is AppUpdateUiState.AwaitingConfirmation }
        awaitCalls(fallbackCalls)
        assertTrue(helperCalls.isEmpty())
        assertEquals(1, fallbackCalls.size)
        assertFalse(updateFile().exists())
    }

    @Test fun helper_result_down_goes_to_fallback() = runBlocking {
        val bytes = "apk-bytes".toByteArray()
        val v = testVariant(bytes)
        val fallbackCalls = Collections.synchronizedList(mutableListOf<File>())
        val u = updater(apkBytes = bytes, variant = v, helperResult = InstallOutcome.HelperDown, fallbackCalls = fallbackCalls)
        u.start(pkg, v, "2")
        u.awaitState { it is AppUpdateUiState.AwaitingConfirmation }
        awaitCalls(fallbackCalls)
    }

    @Test fun disconnected_then_rise_is_done_without_fallback() = runBlocking {
        val bytes = "apk-bytes".toByteArray()
        val v = testVariant(bytes)
        val reads = AtomicInteger()
        val fallbackCalls = Collections.synchronizedList(mutableListOf<File>())
        val u = updater(
            apkBytes = bytes,
            variant = v,
            helperResult = InstallOutcome.Disconnected,
            fallbackCalls = fallbackCalls,
            installed = { (if (reads.incrementAndGet() <= 2) 2L else 3L) to signer },
        )
        u.start(pkg, v, "2")
        val end = u.awaitState { it is AppUpdateUiState.Done }
        assertEquals(AppUpdateUiState.Done("2"), end)
        assertTrue(fallbackCalls.isEmpty())
        assertFalse(updateFile().exists())
    }

    @Test fun disconnected_without_rise_is_failed_not_fallback() = runBlocking {
        val bytes = "apk-bytes".toByteArray()
        val v = testVariant(bytes)
        val fallbackCalls = Collections.synchronizedList(mutableListOf<File>())
        val u = updater(
            apkBytes = bytes,
            variant = v,
            helperResult = InstallOutcome.Disconnected,
            fallbackCalls = fallbackCalls,
        )
        u.start(pkg, v, "2")
        val end = u.awaitState { it is AppUpdateUiState.Failed }
        assertEquals(
            "Couldn't confirm the update. Check the app's version, then try again.",
            (end as AppUpdateUiState.Failed).reason.english(),
        )
        assertTrue(fallbackCalls.isEmpty())
        assertFalse(updateFile().exists())
    }

    @Test fun start_tracks_package() {
        runBlocking {
            val bytes = "apk-bytes".toByteArray()
            val v = testVariant(bytes)
            val u = updater(apkBytes = bytes, variant = v, throttleMs = 10)
            u.start(pkg, v, "2")
            u.awaitState { it is AppUpdateUiState.Downloading }
            assertEquals(pkg, u.pkg.value)
            u.cancel()
            u.awaitState { it is AppUpdateUiState.Idle }
        }
    }

    @Test fun done_keeps_package_until_reset() = runBlocking {
        val bytes = "apk-bytes".toByteArray()
        val v = testVariant(bytes)
        val reads = AtomicInteger()
        val u = updater(apkBytes = bytes, variant = v, installed = { (if (reads.incrementAndGet() <= 2) 2L else 3L) to signer })
        u.start(pkg, v, "2")
        u.awaitState { it is AppUpdateUiState.Done }
        assertEquals(pkg, u.pkg.value)
        u.reset()
        assertEquals(AppUpdateUiState.Idle, u.state.value)
        assertEquals(null, u.pkg.value)
    }

    @Test fun reset_cancels_running_update() = runBlocking {
        val bytes = ByteArray(4 * 1024 * 1024) { it.toByte() }
        val v = testVariant(bytes)
        val u = updater(apkBytes = bytes, variant = v, throttleMs = 10)
        u.start(pkg, v, "2")
        u.awaitState { it is AppUpdateUiState.Downloading }
        u.reset()
        assertEquals(AppUpdateUiState.Idle, u.state.value)
        assertEquals(null, u.pkg.value)
        assertFalse(updateFile().exists())
    }

    @Test fun start_other_package_while_running_is_noop() = runBlocking {
        val bytes = "apk-bytes".toByteArray()
        val v = testVariant(bytes)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val helperCalls = Collections.synchronizedList(mutableListOf<String>())
        val installed = AtomicInteger(2)
        val u = AppUpdater(
            download = UpdateDownload(open = { url -> UpdateDownload.Opened(url, bytes.size.toLong(), bytes.inputStream()) }),
            readApkFacts = { ApkFacts(pkg, v.versionCode, signer) },
            installed = { installed.get().toLong() to signer },
            helperUp = { true },
            helperInstall = { p, _ ->
                helperCalls += p
                entered.complete(Unit)
                release.await()
                installed.set(3)
                InstallOutcome.Started
            },
            fallbackInstall = { InstallOutcome.Started },
            cacheDir = tmp.root,
            freeSpace = { Long.MAX_VALUE },
            confirmTimeoutMs = 2_000,
            confirmPollMs = 5,
        )
        u.start(pkg, v, "2")
        withTimeout(5_000) { entered.await() }
        u.start("com.other.app", v, "2")
        release.complete(Unit)
        val end = u.awaitState { it is AppUpdateUiState.Done }
        assertEquals(AppUpdateUiState.Done("2"), end)
        assertEquals(pkg, u.pkg.value)
        assertEquals(1, helperCalls.size)
    }

    @Test fun version_that_never_rises_is_failed_not_done() = runBlocking {
        val bytes = "apk-bytes".toByteArray()
        val v = testVariant(bytes)
        val fallbackCalls = Collections.synchronizedList(mutableListOf<File>())
        val u = updater(apkBytes = bytes, variant = v, fallbackCalls = fallbackCalls)
        u.start(pkg, v, "2")
        val end = u.awaitState { it is AppUpdateUiState.Failed }
        assertEquals("The update didn't install.", (end as AppUpdateUiState.Failed).reason.english())
        assertTrue(fallbackCalls.isEmpty())
        assertFalse(updateFile().exists())
    }

    @Test fun helper_failed_goes_failed_with_last_line() = runBlocking {
        val bytes = "apk-bytes".toByteArray()
        val v = testVariant(bytes)
        val u = updater(apkBytes = bytes, variant = v, helperResult = InstallOutcome.Failed(3, "boom"))
        u.start(pkg, v, "2")
        val end = u.awaitState { it is AppUpdateUiState.Failed }
        assertEquals("boom", (end as AppUpdateUiState.Failed).reason.english())
        assertFalse(updateFile().exists())
    }

    @Test fun free_space_fail_needs_double_size() = runBlocking {
        val bytes = ByteArray(100) { it.toByte() }
        val v = testVariant(bytes)
        val helperCalls = Collections.synchronizedList(mutableListOf<String>())
        val u = updater(apkBytes = bytes, variant = v, freeBytes = v.size * 2 - 1, helperCalls = helperCalls)
        u.start(pkg, v, "2")
        val end = u.awaitState { it is AppUpdateUiState.Failed }
        assertEquals("Not enough free space on the TV.", (end as AppUpdateUiState.Failed).reason.english())
        assertTrue(helperCalls.isEmpty())
        assertFalse(updateFile().exists())
    }

    @Test fun cancel_during_download_returns_idle_and_deletes() = runBlocking {
        val bytes = ByteArray(4 * 1024 * 1024) { it.toByte() }
        val v = testVariant(bytes)
        val u = updater(apkBytes = bytes, variant = v, throttleMs = 10)
        u.start(pkg, v, "2")
        u.awaitState { it is AppUpdateUiState.Downloading }
        u.cancel()
        u.awaitState { it is AppUpdateUiState.Idle }
        assertFalse(updateFile().exists())
        assertFalse(File(tmp.root, "app-updates/$pkg.apk.tmp").exists())
    }

    @Test fun second_start_while_running_is_noop() = runBlocking {
        val bytes = "apk-bytes".toByteArray()
        val v = testVariant(bytes)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val helperCalls = Collections.synchronizedList(mutableListOf<String>())
        val installed = AtomicInteger(2)
        val u = AppUpdater(
            download = UpdateDownload(open = { url -> UpdateDownload.Opened(url, bytes.size.toLong(), bytes.inputStream()) }),
            readApkFacts = { ApkFacts(pkg, v.versionCode, signer) },
            installed = { installed.get().toLong() to signer },
            helperUp = { true },
            helperInstall = { p, _ ->
                helperCalls += p
                entered.complete(Unit)
                release.await()
                installed.set(3)
                InstallOutcome.Started
            },
            fallbackInstall = { InstallOutcome.Started },
            cacheDir = tmp.root,
            freeSpace = { Long.MAX_VALUE },
            confirmTimeoutMs = 2_000,
            confirmPollMs = 5,
        )
        u.start(pkg, v, "2")
        withTimeout(5_000) { entered.await() }
        u.start(pkg, v, "2")
        release.complete(Unit)
        val end = u.awaitState { it is AppUpdateUiState.Done }
        assertEquals(AppUpdateUiState.Done("2"), end)
        assertEquals(1, helperCalls.size)
    }

    /** Finite body with a per-chunk delay, so cancel lands mid-download. */
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
