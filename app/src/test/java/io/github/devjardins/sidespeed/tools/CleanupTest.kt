package io.github.devjardins.sidespeed.tools

import io.github.devjardins.sidespeed.adb.FakeAdbBackend
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeDeviceInfo(readings: List<StorageInfo>) : DeviceInfo {
    private val queue = ArrayDeque(readings)
    var storageCalls = 0
    override fun storage(): StorageInfo {
        storageCalls++
        return if (queue.size > 1) queue.removeFirst() else queue.first()
    }
    override fun memory(): MemoryInfo = MemoryInfo(availBytes = 0, totalBytes = 0)
    override fun summary(): DeviceSummary = DeviceSummary(model = "", androidVersion = "", sdk = 0)
}

private class FakeToolsStore(threshold: CleanupThreshold = CleanupThreshold.OFF) : ToolsStore {
    val thresholdFlow = MutableStateFlow(threshold)
    val lastFlow = MutableStateFlow<CleanupRecord?>(null)
    val launches = mutableMapOf<String, LaunchResult>()

    override val cleanupThreshold: Flow<CleanupThreshold> = thresholdFlow
    override suspend fun setCleanupThreshold(t: CleanupThreshold) { thresholdFlow.value = t }
    override val lastCleanup: Flow<CleanupRecord?> = lastFlow
    override suspend fun setLastCleanup(r: CleanupRecord) { lastFlow.value = r }
    override fun launchResult(pkg: String): Flow<LaunchResult?> = flowOf(launches[pkg])
    override fun launchResults(): Flow<Map<String, LaunchResult>> = flowOf(launches.toMap())
    override suspend fun setLaunchResult(r: LaunchResult) { launches[r.pkg] = r }
    override val animationOriginals: Flow<AnimationOriginals?> = flowOf(null)
    override suspend fun setAnimationOriginals(o: AnimationOriginals?) {}
    override val restorePending: Flow<RestorePending?> = flowOf(null)
    override suspend fun setRestorePending(r: RestorePending?) {}
    override val updateDismissedVersion: Flow<String?> = flowOf(null)
    override suspend fun setUpdateDismissedVersion(v: String) {}
    override val updateLastCheckMs: Flow<Long> = flowOf(0L)
    override val updateLatestVersion: Flow<String?> = flowOf(null)
    override suspend fun setUpdateCheck(atMs: Long, latest: String?) {}
}

private data class Fixture(
    val fake: FakeAdbBackend,
    val device: FakeDeviceInfo,
    val store: FakeToolsStore,
    val cleanup: Cleanup,
)

class CleanupTest {
    private val atMs = 1_764_000_000_000L
    private val total = 8_000_000_000L

    private fun fixture(
        helperUp: Boolean = true,
        threshold: CleanupThreshold = CleanupThreshold.OFF,
        beforeFree: Long = 5_200_000_000L,
        afterFree: Long = 6_040_000_000L,
    ): Fixture {
        val fake = FakeAdbBackend()
        val commands = ToolsCommands(fake) { helperUp }
        val device = FakeDeviceInfo(
            listOf(StorageInfo(beforeFree, total), StorageInfo(afterFree, total)),
        )
        val store = FakeToolsStore(threshold)
        return Fixture(fake, device, store, Cleanup(commands, device, store) { atMs })
    }

    @Test fun cleanNowTrimsEverythingAndRecordsFreed() = runTest {
        val (fake, _, store, cleanup) = fixture()
        fake.on("pm trim-caches 999999999999999") { emptyList<String>() to 0 }
        val record = cleanup.cleanNow()
        assertEquals(listOf("pm trim-caches 999999999999999"), fake.commands)
        assertEquals(CleanupRecord(atMs = atMs, freedBytes = 840_000_000L, skipped = null), record)
        assertEquals(record, store.lastFlow.value)
    }

    @Test fun cleanNowClampsNegativeFreedToZero() = runTest {
        val (fake, _, store, cleanup) = fixture(beforeFree = 6_000_000_000L, afterFree = 5_000_000_000L)
        fake.on("pm trim-caches 999999999999999") { emptyList<String>() to 0 }
        val record = cleanup.cleanNow()
        assertEquals(CleanupRecord(atMs = atMs, freedBytes = 0L, skipped = null), record)
        assertEquals(record, store.lastFlow.value)
    }

    @Test fun cleanNowHelperDownRecordsSkip() = runTest {
        val (fake, device, store, cleanup) = fixture(helperUp = false)
        val record = cleanup.cleanNow()
        assertEquals(CleanupRecord(atMs = atMs, freedBytes = null, skipped = "Helper not running"), record)
        assertEquals(record, store.lastFlow.value)
        assertTrue(fake.commands.isEmpty())
        assertEquals(1, device.storageCalls)
    }

    @Test fun cleanNowMidCommandDropRecordsHelperNotRunning() = runTest {
        val (fake, _, store, cleanup) = fixture()
        fake.dropAfterLines("pm trim-caches 999999999999999", 0)
        val record = cleanup.cleanNow()
        assertEquals(CleanupRecord(atMs = atMs, freedBytes = null, skipped = "Helper not running"), record)
        assertEquals(record, store.lastFlow.value)
    }

    @Test fun cleanNowHelperOutdatedRecordsRestartSkip() = runTest {
        val (fake, _, store, cleanup) = fixture()
        fake.on("pm trim-caches 999999999999999") { listOf("error: request not allowed") to 126 }
        val record = cleanup.cleanNow()
        assertEquals(CleanupRecord(atMs = atMs, freedBytes = null, skipped = "Restart the helper to use this"), record)
        assertEquals(record, store.lastFlow.value)
    }

    @Test fun cleanNowFailedRecordsMessage() = runTest {
        val (fake, _, store, cleanup) = fixture()
        fake.on("pm trim-caches 999999999999999") { listOf("Failure") to 1 }
        val record = cleanup.cleanNow()
        assertEquals(CleanupRecord(atMs = atMs, freedBytes = null, skipped = "Failure"), record)
        assertEquals(record, store.lastFlow.value)
    }

    @Test fun maybeAutoOffDoesNothing() = runTest {
        val (fake, device, store, cleanup) = fixture(threshold = CleanupThreshold.OFF)
        assertNull(cleanup.maybeAuto())
        assertTrue(fake.commands.isEmpty())
        assertEquals(0, device.storageCalls)
        assertNull(store.lastFlow.value)
    }

    @Test fun maybeAutoEnoughFreeDoesNothing() = runTest {
        val (fake, device, store, cleanup) = fixture(
            threshold = CleanupThreshold.MB_500,
            beforeFree = 600_000_000L,
        )
        assertNull(cleanup.maybeAuto())
        assertTrue(fake.commands.isEmpty())
        assertEquals(1, device.storageCalls)
        assertNull(store.lastFlow.value)
    }

    @Test fun maybeAutoLowFreeTrimsThreshold() = runTest {
        val (fake, _, store, cleanup) = fixture(
            threshold = CleanupThreshold.GB_1,
            beforeFree = 400_000_000L,
            afterFree = 1_200_000_000L,
        )
        fake.on("pm trim-caches 1000000000") { emptyList<String>() to 0 }
        val record = cleanup.maybeAuto()
        assertEquals(listOf("pm trim-caches 1000000000"), fake.commands)
        assertEquals(CleanupRecord(atMs = atMs, freedBytes = 800_000_000L, skipped = null), record)
        assertEquals(record, store.lastFlow.value)
    }

    @Test fun maybeAutoHelperDownRecordsSkip() = runTest {
        val (fake, _, store, cleanup) = fixture(
            helperUp = false,
            threshold = CleanupThreshold.GB_2,
            beforeFree = 400_000_000L,
        )
        val record = cleanup.maybeAuto()
        assertEquals(CleanupRecord(atMs = atMs, freedBytes = null, skipped = "Helper not running"), record)
        assertEquals(record, store.lastFlow.value)
        assertTrue(fake.commands.isEmpty())
    }

    @Test fun maybeAutoHelperOutdatedRecordsRestartSkip() = runTest {
        val (fake, _, store, cleanup) = fixture(
            threshold = CleanupThreshold.MB_500,
            beforeFree = 400_000_000L,
        )
        fake.on("pm trim-caches 500000000") { listOf("error: request not allowed") to 126 }
        val record = cleanup.maybeAuto()
        assertEquals(CleanupRecord(atMs = atMs, freedBytes = null, skipped = "Restart the helper to use this"), record)
        assertEquals(record, store.lastFlow.value)
    }

    @Test fun running_true_during_clean_and_false_after() {
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val blockingDevice = object : DeviceInfo {
            override fun storage(): StorageInfo {
                entered.countDown()
                release.await(5, java.util.concurrent.TimeUnit.SECONDS)
                return StorageInfo(5_200_000_000L, 8_000_000_000L)
            }
            override fun memory(): MemoryInfo = MemoryInfo(availBytes = 0, totalBytes = 0)
            override fun summary(): DeviceSummary = DeviceSummary(model = "", androidVersion = "", sdk = 0)
        }
        val fake = FakeAdbBackend()
        val commands = ToolsCommands(fake) { true }
        val cleanup = Cleanup(commands, blockingDevice, FakeToolsStore(threshold = CleanupThreshold.OFF)) { atMs }
        fake.on("pm trim-caches 999999999999999") { emptyList<String>() to 0 }
        assertFalse(cleanup.running.value)
        val t = kotlin.concurrent.thread {
            kotlinx.coroutines.runBlocking { cleanup.cleanNow() }
        }
        assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
        assertTrue(cleanup.running.value)
        release.countDown()
        t.join(5_000)
        assertFalse(cleanup.running.value)
    }
}
