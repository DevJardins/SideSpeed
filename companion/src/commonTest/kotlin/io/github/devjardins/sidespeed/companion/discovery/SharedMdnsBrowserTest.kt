package io.github.devjardins.sidespeed.companion.discovery

import com.flyfishxu.kadb.mdns.KadbMdns
import com.flyfishxu.kadb.mdns.MdnsDiscoveryState
import com.flyfishxu.kadb.mdns.MdnsStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SharedMdnsBrowserTest {
    @Test
    fun twoLookupsInARowStartOneBrowser() = runTest {
        var opens = 0
        val browser = browser(open = { opens += 1; quietMdns() }, clock = { 0L }, stopScope = this)
        browser.services()
        browser.services()
        assertEquals(1, opens)
        coroutineContext[Job]?.cancelChildren()
    }

    @Test
    fun afterTheIdleTimeItStopsOnce() = runTest {
        var now = 0L
        var opens = 0
        var stops = 0
        val browser = browser(
            open = { opens += 1; quietMdns(onStop = { stops += 1 }) },
            clock = { now },
            stopScope = this,
        )
        browser.services()
        browser.services()
        assertEquals(1, opens)
        now = 59_000
        advanceTimeBy(SharedMdnsBrowser.POLL_MS)
        runCurrent()
        assertEquals(0, stops)
        now = SharedMdnsBrowser.IDLE_MS
        advanceTimeBy(SharedMdnsBrowser.POLL_MS)
        runCurrent()
        assertEquals(1, stops)
        advanceTimeBy(SharedMdnsBrowser.POLL_MS * 5)
        runCurrent()
        assertEquals(1, stops)
        // A lookup after the stop starts a new browser, and does not stop it before its own idle time.
        browser.services()
        assertEquals(2, opens)
        advanceTimeBy(SharedMdnsBrowser.POLL_MS)
        runCurrent()
        assertEquals(1, stops)
        coroutineContext[Job]?.cancelChildren()
    }

    @Test
    fun aLookupDuringAStopStartsAFreshBrowser() {
        val stopEntered = CountDownLatch(1)
        val releaseStop = CountDownLatch(1)
        var opens = 0
        val caller = daemonDispatcher("mdns-caller")
        val stopper = daemonDispatcher("mdns-stop")
        val stopScope = CoroutineScope(SupervisorJob() + stopper)
        var now = 0L
        val browser = browser(
            open = {
                opens += 1
                quietMdns(onStop = {
                    stopEntered.countDown()
                    assertTrue(releaseStop.await(5, TimeUnit.SECONDS))
                })
            },
            clock = { now },
            stopScope = stopScope,
            pollMs = 20,
        )
        try {
            runBlocking(caller) { browser.services() }
            now = SharedMdnsBrowser.IDLE_MS
            assertTrue(stopEntered.await(2, TimeUnit.SECONDS))
            val second = Thread {
                runBlocking { browser.services() }
            }
            second.isDaemon = true
            second.start()
            second.join(1_000)
            assertEquals(false, second.isAlive, "lookup waited for the browser to finish stopping")
            assertEquals(2, opens)
        } finally {
            releaseStop.countDown()
            caller.close()
            stopper.close()
        }
    }

    @Test
    fun stopNeverRunsOnTheCallersCoroutine() {
        val stopped = CountDownLatch(1)
        var stopThread: String? = null
        val caller = daemonDispatcher("mdns-caller")
        val stopper = daemonDispatcher("mdns-stop")
        val stopScope = CoroutineScope(SupervisorJob() + stopper)
        var now = 0L
        val browser = browser(
            open = {
                quietMdns(onStop = {
                    stopThread = Thread.currentThread().name
                    stopped.countDown()
                })
            },
            clock = { now },
            stopScope = stopScope,
            pollMs = 20,
        )
        try {
            val callerThread = runBlocking(caller) {
                browser.services()
                Thread.currentThread().name
            }
            now = SharedMdnsBrowser.IDLE_MS
            assertTrue(stopped.await(2, TimeUnit.SECONDS))
            // Debug mode appends " @coroutine#N" while that coroutine is running.
            assertEquals("mdns-stop", stopThread?.substringBefore(" @"))
            assertEquals("mdns-caller", callerThread.substringBefore(" @"))
            assertNotEquals(callerThread, stopThread)
        } finally {
            caller.close()
            stopper.close()
        }
    }

    private fun browser(
        open: suspend () -> KadbMdns,
        clock: () -> Long,
        stopScope: CoroutineScope,
        pollMs: Long = SharedMdnsBrowser.POLL_MS,
    ) = SharedMdnsBrowser(open, clock, SharedMdnsBrowser.IDLE_MS, stopScope, pollMs)

    private fun quietMdns(onStop: () -> Unit = {}) = object : KadbMdns {
        override val state = MutableStateFlow(
            MdnsDiscoveryState(MdnsStatus.STARTED, false, emptyList(), emptyList()),
        )
        override fun start() {}
        override fun stop() = onStop()
    }

    private fun daemonDispatcher(name: String) =
        Executors.newSingleThreadExecutor { runnable -> Thread(runnable, name).apply { isDaemon = true } }
            .asCoroutineDispatcher()
}
