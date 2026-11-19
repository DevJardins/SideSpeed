package io.github.devjardins.sidespeed.ui.about

import io.github.devjardins.sidespeed.tools.FakeUpdateStore
import io.github.devjardins.sidespeed.tools.ManualCheckResult
import io.github.devjardins.sidespeed.tools.UpdateCheck
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AboutViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private val newerJson = """{"tag_name":"v0.5.0"}"""

    private fun vm(
        store: FakeUpdateStore,
        fetch: suspend () -> String = { newerJson },
        currentVersion: String = "0.4.0",
        started: MutableList<String> = mutableListOf(),
    ) = AboutViewModel(
        check = UpdateCheck(fetch, store, { 1_000L }, currentVersion),
        startUpdate = { started += it },
        ioDispatcher = dispatcher,
    )

    @Test fun newerShowsPrompt() = runTest {
        val store = FakeUpdateStore()
        val v = vm(store)
        v.checkNow()
        dispatcher.scheduler.runCurrent()
        assertEquals(ManualCheckResult.Newer("v0.5.0"), v.state.value.result)
        assertEquals("v0.5.0", v.state.value.promptTag)
    }

    @Test fun laterHidesPromptWithoutStoringDismissal() = runTest {
        val store = FakeUpdateStore()
        store.setUpdateDismissedVersion("v0.4.0")
        val started = mutableListOf<String>()
        val v = vm(store, started = started)
        v.checkNow()
        dispatcher.scheduler.runCurrent()
        assertEquals("v0.5.0", v.state.value.promptTag)
        v.dismissPrompt()
        assertNull(v.state.value.promptTag)
        // Later stored nothing: the dismissed version is untouched and no update started.
        assertEquals("v0.4.0", store.updateDismissedVersion.first())
        assertTrue(started.isEmpty())
    }

    @Test fun upToDateShowsNoPrompt() = runTest {
        val store = FakeUpdateStore()
        val v = vm(store, currentVersion = "0.5.0")
        v.checkNow()
        dispatcher.scheduler.runCurrent()
        assertEquals(ManualCheckResult.UpToDate, v.state.value.result)
        assertNull(v.state.value.promptTag)
    }

    @Test fun failedShowsNoPrompt() = runTest {
        val store = FakeUpdateStore()
        val v = vm(store, fetch = { throw java.io.IOException("offline") })
        v.checkNow()
        dispatcher.scheduler.runCurrent()
        assertEquals(ManualCheckResult.Failed, v.state.value.result)
        assertNull(v.state.value.promptTag)
    }

    @Test fun updateNowStartsSharedFlowForPromptedTag() = runTest {
        val store = FakeUpdateStore()
        val started = mutableListOf<String>()
        val v = vm(store, started = started)
        v.checkNow()
        dispatcher.scheduler.runCurrent()
        v.onUpdateNow()
        assertEquals(listOf("v0.5.0"), started)
        // The prompt stays up so Downloading/Installing/Failed can show in place.
        assertEquals("v0.5.0", v.state.value.promptTag)
    }
}
