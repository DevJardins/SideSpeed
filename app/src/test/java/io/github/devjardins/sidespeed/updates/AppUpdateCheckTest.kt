package io.github.devjardins.sidespeed.updates

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** In-memory [AppUpdateStore] for catalog-check tests. */
class FakeAppUpdateStore : AppUpdateStore {
    private val body = MutableStateFlow<String?>(null)
    private val tag = MutableStateFlow<String?>(null)
    private val check = MutableStateFlow(0L)

    override val json: Flow<String?> = body
    override val etag: Flow<String?> = tag
    override val lastCheckMs: Flow<Long> = check
    override suspend fun setCheck(atMs: Long, json: String?, etag: String?) {
        check.value = atMs
        body.value = json
        tag.value = etag
    }
}

class AppUpdateCheckTest {
    private val good = """{"schema":1,"generated":"t","apps":{"a.b":{"name":"A","versionName":"2"}}}"""

    @Test fun intervalIs24Hours() {
        assertEquals(24 * 60 * 60 * 1000L, APP_UPDATES_INTERVAL_MS)
    }

    @Test fun checkSkippedWhenCheckedRecently() = runTest {
        val store = FakeAppUpdateStore()
        var calls = 0
        store.setCheck(1_000L, good, "etag-1")
        val check = AppUpdateCheck(
            fetch = { calls++; AppUpdateOutcome.Data(good, "etag-2") },
            store = store,
            clock = { 1_000L + 60_000L },
        )
        check.checkIfDue()
        assertEquals(0, calls)
    }

    @Test fun checkSkippedJustBefore24Hours() = runTest {
        val store = FakeAppUpdateStore()
        var calls = 0
        store.setCheck(1_000L, good, null)
        val check = AppUpdateCheck(
            fetch = { calls++; AppUpdateOutcome.Data(good, null) },
            store = store,
            clock = { 1_000L + APP_UPDATES_INTERVAL_MS - 1L },
        )
        check.checkIfDue()
        assertEquals(0, calls)
    }

    @Test fun checkFetchesAt24Hours() = runTest {
        val store = FakeAppUpdateStore()
        store.setCheck(1_000L, null, null)
        val check = AppUpdateCheck(
            fetch = { AppUpdateOutcome.Data(good, "etag-1") },
            store = store,
            clock = { 1_000L + APP_UPDATES_INTERVAL_MS },
        )
        check.checkIfDue()
        assertEquals(good, store.json.first())
        assertEquals("etag-1", store.etag.first())
    }

    @Test fun notModifiedKeepsBodyAndAdvancesTime() = runTest {
        val store = FakeAppUpdateStore()
        store.setCheck(1_000L, good, "etag-1")
        var seenEtag: String? = "unset"
        val now = 1_000L + APP_UPDATES_INTERVAL_MS
        val check = AppUpdateCheck(
            fetch = { etag -> seenEtag = etag; AppUpdateOutcome.NotModified },
            store = store,
            clock = { now },
        )
        check.checkIfDue()
        assertEquals("etag-1", seenEtag)
        assertEquals(good, store.json.first())
        assertEquals("etag-1", store.etag.first())
        assertEquals(now, store.lastCheckMs.first())
    }

    @Test fun fetchFailureIsSilentAndKeepsCopy() = runTest {
        val store = FakeAppUpdateStore()
        store.setCheck(1_000L, good, "etag-1")
        val now = 1_000L + APP_UPDATES_INTERVAL_MS
        val check = AppUpdateCheck(
            fetch = { throw java.io.IOException("offline") },
            store = store,
            clock = { now },
        )
        check.checkIfDue() // must not throw
        assertEquals(now, store.lastCheckMs.first())
        assertEquals(good, store.json.first())
        assertEquals("etag-1", store.etag.first())
    }

    @Test fun failedOutcomeKeepsCopy() = runTest {
        val store = FakeAppUpdateStore()
        store.setCheck(1_000L, good, null)
        val now = 1_000L + APP_UPDATES_INTERVAL_MS
        val check = AppUpdateCheck(
            fetch = { AppUpdateOutcome.Failed },
            store = store,
            clock = { now },
        )
        check.checkIfDue()
        assertEquals(good, store.json.first())
        assertEquals(now, store.lastCheckMs.first())
    }

    @Test fun badBodyDoesNotClobberGoodCopy() = runTest {
        val store = FakeAppUpdateStore()
        store.setCheck(1_000L, good, "etag-1")
        val now = 1_000L + APP_UPDATES_INTERVAL_MS
        val check = AppUpdateCheck(
            fetch = { AppUpdateOutcome.Data("""{"schema":2,"apps":{}}""", "etag-2") },
            store = store,
            clock = { now },
        )
        check.checkIfDue()
        assertEquals(good, store.json.first())
        assertEquals("etag-1", store.etag.first())
        assertEquals(now, store.lastCheckMs.first())
    }

    @Test fun firstSuccessStoresEmptyCatalogBody() = runTest {
        val store = FakeAppUpdateStore()
        val empty = """{"schema":1,"generated":"t","apps":{}}"""
        val check = AppUpdateCheck(
            fetch = { AppUpdateOutcome.Data(empty, null) },
            store = store,
            clock = { APP_UPDATES_INTERVAL_MS },
        )
        check.checkIfDue()
        assertEquals(empty, store.json.first())
        assertNull(store.etag.first())
    }
}
