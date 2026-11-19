package io.github.devjardins.sidespeed.tools

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** In-memory [ToolsStore] for update-check tests. */
class FakeUpdateStore : ToolsStore {
    private val threshold = MutableStateFlow(CleanupThreshold.OFF)
    private val cleanup = MutableStateFlow<CleanupRecord?>(null)
    private val launches = MutableStateFlow<Map<String, LaunchResult>>(emptyMap())
    private val originals = MutableStateFlow<AnimationOriginals?>(null)
    private val dismissed = MutableStateFlow<String?>(null)
    private val lastCheck = MutableStateFlow(0L)
    private val latest = MutableStateFlow<String?>(null)

    override val cleanupThreshold: Flow<CleanupThreshold> = threshold
    override suspend fun setCleanupThreshold(t: CleanupThreshold) { threshold.value = t }
    override val lastCleanup: Flow<CleanupRecord?> = cleanup
    override suspend fun setLastCleanup(r: CleanupRecord) { cleanup.value = r }
    override fun launchResult(pkg: String): Flow<LaunchResult?> = MutableStateFlow(launches.value[pkg])
    override fun launchResults(): Flow<Map<String, LaunchResult>> = launches
    override suspend fun setLaunchResult(r: LaunchResult) { launches.value += r.pkg to r }
    override val animationOriginals: Flow<AnimationOriginals?> = originals
    override suspend fun setAnimationOriginals(o: AnimationOriginals?) { originals.value = o }
    override val restorePending: Flow<RestorePending?> = MutableStateFlow(null)
    override suspend fun setRestorePending(r: RestorePending?) {}
    override val updateDismissedVersion: Flow<String?> = dismissed
    override suspend fun setUpdateDismissedVersion(v: String) { dismissed.value = v }
    override suspend fun clearUpdateDismissedVersion() { dismissed.value = null }
    override val updateLastCheckMs: Flow<Long> = lastCheck
    override val updateLatestVersion: Flow<String?> = latest
    override suspend fun setUpdateCheck(atMs: Long, latest: String?) {
        lastCheck.value = atMs
        this.latest.value = latest
    }
    override val originalHome: Flow<String?> = MutableStateFlow(null)
    override suspend fun setOriginalHome(v: String?) {}
}

class UpdateCheckTest {
    private val sampleJson = """
        {
          "url": "https://api.github.com/repos/DevJardins/SideSpeed/releases/1",
          "tag_name": "v0.5.0",
          "name": "SideSpeed 0.5.0",
          "draft": false,
          "prerelease": false
        }
    """.trimIndent()

    @Test fun dottedCompareIsNumeric() {
        assertTrue(compareVersions("0.10.0", "0.9.0") > 0)
        assertTrue(compareVersions("0.9.0", "0.10.0") < 0)
        assertEquals(0, compareVersions("0.4.0", "0.4.0"))
    }

    @Test fun leadingVIsIgnored() {
        assertEquals(0, compareVersions("v0.4.0", "0.4.0"))
        assertTrue(compareVersions("v0.5.0", "0.4.0") > 0)
    }

    @Test fun preReleaseSuffixTreatedAsBase() {
        assertEquals(0, compareVersions("0.4.0-beta", "0.4.0"))
    }

    @Test fun garbageMeansNoNotice() {
        assertFalse(shouldShowBanner("garbage", null, "0.4.0"))
        assertFalse(shouldShowBanner("vX", null, "0.4.0"))
    }

    @Test fun parseLatestTagReadsRealSample() {
        assertEquals("v0.5.0", parseLatestTag(sampleJson))
    }

    @Test fun parseLatestTagMissingIsNull() {
        assertNull(parseLatestTag("""{"name": "no tag here"}"""))
        assertNull(parseLatestTag("not json at all"))
    }

    private fun releaseDownload(body: ByteArray) = io.github.devjardins.sidespeed.update.UpdateDownload(
        open = {
            io.github.devjardins.sidespeed.update.UpdateDownload.Opened(
                "https://api.github.com/repos/DevJardins/SideSpeed/releases/latest",
                body.size.toLong(),
                java.io.ByteArrayInputStream(body),
            )
        },
    )

    @Test fun release_fetch_reads_small_body() = runTest {
        val body = """{"tag_name":"v0.5.0"}""".toByteArray()
        assertEquals(String(body), githubReleaseFetch(releaseDownload(body))())
    }

    @Test fun release_fetch_caps_body_at_256k() = runTest {
        // Exactly the cap still reads; one byte over must throw so UpdateCheck stays silent.
        val cap = 256 * 1024
        assertEquals(cap, githubReleaseFetch(releaseDownload(ByteArray(cap)))().length)
        try {
            githubReleaseFetch(releaseDownload(ByteArray(cap + 1)))()
            fail("expected cap overflow")
        } catch (_: IllegalStateException) {
        }
    }

    @Test fun checkSkippedWhenCheckedRecently() = runTest {
        val store = FakeUpdateStore()
        var calls = 0
        store.setUpdateCheck(1_000L, "0.4.0")
        val check = UpdateCheck(
            fetch = { calls++; sampleJson },
            store = store,
            clock = { 1_000L + 60_000L },
            currentVersion = "0.4.0",
        )
        check.checkIfDue()
        assertEquals(0, calls)
    }

    @Test fun intervalIsSixHours() {
        assertEquals(6 * 60 * 60 * 1000L, UPDATE_CHECK_INTERVAL_MS)
    }

    @Test fun checkSkippedJustBeforeSixHours() = runTest {
        val store = FakeUpdateStore()
        var calls = 0
        store.setUpdateCheck(1_000L, "0.4.0")
        val check = UpdateCheck(
            fetch = { calls++; sampleJson },
            store = store,
            clock = { 1_000L + UPDATE_CHECK_INTERVAL_MS - 1L },
            currentVersion = "0.4.0",
        )
        check.checkIfDue()
        assertEquals(0, calls)
    }

    @Test fun checkFetchesAtSixHours() = runTest {
        val store = FakeUpdateStore()
        store.setUpdateCheck(1_000L, null)
        val check = UpdateCheck(
            fetch = { sampleJson },
            store = store,
            clock = { 1_000L + UPDATE_CHECK_INTERVAL_MS },
            currentVersion = "0.4.0",
        )
        check.checkIfDue()
        assertEquals("v0.5.0", store.updateLatestVersion.first())
    }

    @Test fun checkFetchesWhenDueAndStoresLatest() = runTest {
        val store = FakeUpdateStore()
        store.setUpdateCheck(0L, null)
        val check = UpdateCheck(
            fetch = { sampleJson },
            store = store,
            clock = { 25 * 60 * 60 * 1000L },
            currentVersion = "0.4.0",
        )
        check.checkIfDue()
        assertEquals("v0.5.0", store.updateLatestVersion.first())
    }

    @Test fun fetchFailureIsSilent() = runTest {
        val store = FakeUpdateStore()
        store.setUpdateCheck(0L, "0.4.0")
        val now = 25 * 60 * 60 * 1000L
        val check = UpdateCheck(
            fetch = { throw java.io.IOException("offline") },
            store = store,
            clock = { now },
            currentVersion = "0.4.0",
        )
        check.checkIfDue() // must not throw
        // No retry storm: the attempt still advances the last-check time, keeping the old latest.
        assertEquals(now, store.updateLastCheckMs.first())
        assertEquals("0.4.0", store.updateLatestVersion.first())
    }

    @Test fun displayVersionStripsVPrefix() {
        assertEquals("0.5.0", displayVersion("v0.5.0"))
        assertEquals("0.5.0", displayVersion("0.5.0"))
    }

    @Test fun checkNowUpToDateRestartsTimer() = runTest {
        val store = FakeUpdateStore()
        store.setUpdateCheck(0L, null)
        val now = 25 * 60 * 60 * 1000L
        val check = UpdateCheck(
            fetch = { sampleJson },
            store = store,
            clock = { now },
            currentVersion = "0.5.0",
        )
        assertEquals(ManualCheckResult.UpToDate, check.checkNow())
        assertEquals(now, store.updateLastCheckMs.first())
        assertEquals("v0.5.0", store.updateLatestVersion.first())
    }

    @Test fun checkNowNewer() = runTest {
        val store = FakeUpdateStore()
        store.setUpdateCheck(0L, null)
        val check = UpdateCheck(
            fetch = { sampleJson },
            store = store,
            clock = { 25 * 60 * 60 * 1000L },
            currentVersion = "0.4.0",
        )
        assertEquals(ManualCheckResult.Newer("v0.5.0"), check.checkNow())
        assertEquals("v0.5.0", store.updateLatestVersion.first())
    }

    @Test fun checkNowClearsMatchingDismissal() = runTest {
        val store = FakeUpdateStore()
        store.setUpdateCheck(0L, "v0.5.0")
        store.setUpdateDismissedVersion("v0.5.0")
        val check = UpdateCheck(
            fetch = { sampleJson },
            store = store,
            clock = { 25 * 60 * 60 * 1000L },
            currentVersion = "0.4.0",
        )
        assertEquals(ManualCheckResult.Newer("v0.5.0"), check.checkNow())
        assertNull(store.updateDismissedVersion.first())
    }

    @Test fun checkNowFetchFailureKeepsLatest() = runTest {
        val store = FakeUpdateStore()
        store.setUpdateCheck(0L, "0.4.0")
        val now = 25 * 60 * 60 * 1000L
        val check = UpdateCheck(
            fetch = { throw java.io.IOException("offline") },
            store = store,
            clock = { now },
            currentVersion = "0.4.0",
        )
        assertEquals(ManualCheckResult.Failed, check.checkNow())
        assertEquals(now, store.updateLastCheckMs.first())
        assertEquals("0.4.0", store.updateLatestVersion.first())
    }

    @Test fun checkNowUnparsableIsFailed() = runTest {
        val store = FakeUpdateStore()
        store.setUpdateCheck(0L, "0.4.0")
        val check = UpdateCheck(
            fetch = { """{"name": "no tag here"}""" },
            store = store,
            clock = { 25 * 60 * 60 * 1000L },
            currentVersion = "0.4.0",
        )
        assertEquals(ManualCheckResult.Failed, check.checkNow())
        assertEquals("0.4.0", store.updateLatestVersion.first())
    }

    @Test fun checkNowIgnoresThrottle() = runTest {
        val store = FakeUpdateStore()
        store.setUpdateCheck(1_000L, "v0.5.0")
        var calls = 0
        val check = UpdateCheck(
            fetch = { calls++; sampleJson },
            store = store,
            clock = { 1_000L + 60_000L },
            currentVersion = "0.5.0",
        )
        assertEquals(ManualCheckResult.UpToDate, check.checkNow())
        assertEquals(1, calls)
        assertEquals(1_000L + 60_000L, store.updateLastCheckMs.first())
    }

    @Test fun bannerHiddenWhenDismissed() {
        assertFalse(shouldShowBanner("v0.5.0", "v0.5.0", "0.4.0"))
        assertTrue(shouldShowBanner("v0.5.0", "v0.4.0", "0.4.0"))
        assertTrue(shouldShowBanner("v0.5.0", null, "0.4.0"))
        assertFalse(shouldShowBanner("v0.4.0", null, "0.4.0"))
        assertFalse(shouldShowBanner(null, null, "0.4.0"))
    }
}
