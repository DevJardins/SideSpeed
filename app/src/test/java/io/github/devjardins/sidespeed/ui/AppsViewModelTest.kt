package io.github.devjardins.sidespeed.ui

import io.github.devjardins.sidespeed.apps.AppEntry
import io.github.devjardins.sidespeed.apps.AppSource
import io.github.devjardins.sidespeed.ui.apps.AppsViewModel
import io.github.devjardins.sidespeed.ui.apps.ManualResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.Executors

@OptIn(ExperimentalCoroutinesApi::class)
class AppsViewModelTest {
    private val nuvio = AppEntry("com.nuvio.tv", "Nuvio", null, 1L)
    private val store = AppEntry("com.store.app", "Store", "com.android.vending", 2L)

    private class Source(val apps: List<AppEntry>) : AppSource {
        var listThread: String? = null
        override fun list(): List<AppEntry> { listThread = Thread.currentThread().name; return apps }
        override fun find(pkg: String): AppEntry? = apps.firstOrNull { it.packageName == pkg }
    }

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private fun vm(src: AppSource) = AppsViewModel(src, Dispatchers.Unconfined)

    @Test fun manualEntryValidation() {
        val v = vm(Source(listOf(nuvio)))
        assertEquals(ManualResult.Invalid, v.manualEntry("com.foo; reboot"))
        assertEquals(ManualResult.NotInstalled, v.manualEntry("com.not.here"))
        assertEquals(ManualResult.Valid(nuvio), v.manualEntry(" com.nuvio.tv "))
    }

    @Test fun listRunsOnInjectedIoDispatcherNotMain() {
        val exec = Executors.newSingleThreadExecutor { Thread(it, "io-test") }
        try {
            val src = Source(listOf(store, nuvio))
            val v = AppsViewModel(src, exec.asCoroutineDispatcher())
            v.load()
            runBlocking { withTimeout(10_000) { v.state.first { !it.loading } } }
            assertTrue(src.listThread!!.startsWith("io-test"))
            assertEquals(listOf(nuvio, store), v.state.value.apps)
            assertTrue(v.state.value.apps.first().isSideloaded)
        } finally {
            exec.shutdownNow()
        }
    }
}
