package io.github.devjardins.sidespeed.helper

import io.github.devjardins.sidespeed.adb.AdbConnectionLost
import io.github.devjardins.sidespeed.adb.ShellEvent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.rules.Timeout
import java.io.File
import java.net.ConnectException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class HelperClientTest {
    @get:Rule val tmp = TemporaryFolder()

    /** Every test runs on its own thread and fails after 10 s instead of hanging the build. */
    @get:Rule val timeout: Timeout = Timeout.seconds(10)

    private val loopback = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
    private val server = ServerSocket(0, 50, loopback).apply { soTimeout = 1_000 }
    private val requests: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val connectPorts: MutableList<Int> = Collections.synchronizedList(mutableListOf())
    private val logs: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val portFile by lazy { tmp.root.resolve("sidespeed_helper.port") }
    private val ackFile by lazy { tmp.root.resolve("sidespeed_helper.ack") }

    /** What the fake helper does with each request; the default behaves like the real helper. */
    @Volatile private var handler: (String, Socket) -> Unit = ::realHelper

    @Volatile private var pong = "pong v=1 uid=2000 apk=/x"

    /** When set, the fake writes this to the ack file instead of the nonce it was sent. */
    @Volatile private var ackOverride: String? = null

    init {
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val s = try {
                    server.accept()
                } catch (_: SocketTimeoutException) {
                    continue
                } catch (_: Exception) {
                    break
                }
                s.soTimeout = SERVER_READ_TIMEOUT_MS
                thread(isDaemon = true) {
                    s.use {
                        val req = it.getInputStream().bufferedReader().readLine() ?: return@use
                        requests += req
                        handler(req, it)
                    }
                }
            }
        }
    }

    @After fun tearDown() = server.close()

    private fun realHelper(req: String, s: Socket) {
        when {
            req.startsWith("ping ") -> {
                ackFile.writeText((ackOverride ?: req.removePrefix("ping ")) + "\n")
                reply(s, pong, "__END__:0")
            }
            req == "dumpsys package dexopt" -> reply(s, "Dexopt state:", "  [com.nuvio.tv]", "__END__:0")
            req.startsWith("pm compile") -> reply(s, "Failure: x", "__END__:1")
            else -> reply(s, "error: request not allowed", "__END__:126")
        }
    }

    private fun reply(s: Socket, vararg lines: String) {
        val w = s.getOutputStream()
        lines.forEach { w.write((it + "\n").toByteArray()) }
        w.flush()
    }

    private fun client(
        connect: (Int) -> Socket = { p -> connectPorts += p; Socket(loopback, server.localPort) },
        retryDelaysMs: List<Long> = listOf(1, 1),
        immediateCloseWindowMs: Long = 300,
    ) = HelperClient(
        portFile = portFile,
        connect = connect,
        ackFile = ackFile,
        retryDelaysMs = retryDelaysMs,
        immediateCloseWindowMs = immediateCloseWindowMs,
        log = { logs += it },
    )

    private fun writePort(text: String = "47310\n") = portFile.writeText(text)

    @Test fun pingParsesPong() = runBlocking {
        writePort()
        val c = client()
        assertEquals(HelperPing(1, "/x"), c.ping())
        assertEquals(listOf(47310), connectPorts.toList())
        assertTrue(requests.single(), Regex("^ping [A-Za-z0-9]{32}$").matches(requests.single()))
    }

    @Test fun eachPingUsesAFreshNonce() = runBlocking {
        writePort()
        val c = client()
        c.ping()
        c.ping()
        assertEquals(2, requests.size)
        assertNotEquals(requests[0], requests[1])
    }

    @Test fun pingReturnsNullWhenAckHoldsADifferentNonce() = runBlocking {
        writePort()
        ackOverride = "someoneElsesNonce0123456789abcde"
        val c = client()
        assertNull(c.ping())
        assertTrue(logs.toString(), logs.any { it.contains("ack") })
    }

    @Test fun pingReturnsNullWhenAckFileMissing() = runBlocking {
        writePort()
        handler = { _, s -> reply(s, pong, "__END__:0") } // impersonator: cannot write the ack file
        assertNull(client().ping())
    }

    @Test fun missingPortFileGivesNull() = runBlocking {
        assertNull(client().ping())
        assertTrue(connectPorts.isEmpty())
    }

    @Test fun badPortFileGivesNull() = runBlocking {
        for (text in listOf("", "abc", "80", "47320", "47309", "-1")) {
            writePort(text)
            assertNull(text, client().ping())
        }
        assertTrue(connectPorts.isEmpty())
        writePort("47319")
        assertEquals(HelperPing(1, "/x"), client().ping())
    }

    @Test fun versionMismatchGivesNull() = runBlocking {
        writePort()
        pong = "pong v=2 uid=2000 apk=/x"
        assertNull(client().ping())
    }

    @Test fun refusedPingGivesNull() = runBlocking {
        writePort()
        handler = { _, s -> reply(s, "error: request not allowed", "__END__:126") }
        assertNull(client().ping())
    }

    @Test fun apkPathWithTildesAndEquals() = runBlocking {
        writePort()
        val path = "/data/app/~~Ab3_x-Y==/io.github.devjardins.sidespeed-Qz9==/base.apk"
        pong = "pong v=1 uid=2000 apk=$path"
        assertEquals(HelperPing(1, path), client().ping())
    }

    @Test fun parsePong() {
        assertEquals(HelperPing(1, "/x"), HelperPing.parse("pong v=1 uid=2000 apk=/x"))
        assertEquals(HelperPing(3, "/a b=c"), HelperPing.parse("pong v=3 uid=2000 apk=/a b=c"))
        listOf("pong", "pong v=1", "pong v=x uid=2000 apk=/x", "pong v=1 uid=2000 apk=", "ping v=1 uid=2000 apk=/x", "")
            .forEach { assertNull(it, HelperPing.parse(it)) }
    }

    @Test fun isCurrentComparesApkPath() {
        assertTrue(HelperPing(1, "/x").isCurrent("/x"))
        assertFalse(HelperPing(1, "/x").isCurrent("/y"))
    }

    @Test fun shellGivesLinesThenExit() = runBlocking {
        writePort()
        val c = client()
        c.ping()
        assertEquals(
            listOf(ShellEvent.Line("Dexopt state:"), ShellEvent.Line("  [com.nuvio.tv]"), ShellEvent.Exit(0)),
            c.shell("dumpsys package dexopt").toList(),
        )
        assertEquals("dumpsys package dexopt", requests.last())
    }

    @Test fun shellPassesNonZeroExit() = runBlocking {
        writePort()
        val c = client()
        c.ping()
        assertEquals(
            listOf(ShellEvent.Line("Failure: x"), ShellEvent.Exit(1)),
            c.shell("pm compile -m speed -f com.nuvio.tv").toList(),
        )
    }

    @Test fun serverClosingBeforeEndThrows() = runBlocking {
        writePort()
        val c = client()
        c.ping()
        handler = { _, s -> reply(s, "partial") }
        val seen = mutableListOf<ShellEvent>()
        try {
            c.shell("dumpsys package dexopt").collect { seen += it }
            fail("expected AdbConnectionLost")
        } catch (_: AdbConnectionLost) {
        }
        assertEquals(listOf(ShellEvent.Line("partial")), seen)
    }

    @Test fun shellWithoutVerifiedPingThrows() = runBlocking {
        writePort()
        try {
            client().shell("dumpsys package dexopt").toList()
            fail("expected AdbConnectionLost")
        } catch (e: AdbConnectionLost) {
            assertTrue(e.message, e.message!!.contains("helper not verified"))
        }
        assertTrue(connectPorts.isEmpty())
    }

    @Test fun shellAfterFailedVerificationThrows() = runBlocking {
        writePort()
        val c = client()
        assertEquals(HelperPing(1, "/x"), c.ping())
        ackOverride = "someoneElsesNonce0123456789abcde"
        assertNull(c.ping())
        try {
            c.shell("dumpsys package dexopt").toList()
            fail("expected AdbConnectionLost")
        } catch (_: AdbConnectionLost) {
        }
        assertEquals(2, requests.size) // nothing sent after the failed ping
    }

    @Test fun shellUsesTheVerifiedPortEvenIfThePortFileChanges() = runBlocking {
        writePort("47312")
        val c = client()
        c.ping()
        writePort("47315")
        c.shell("dumpsys package dexopt").toList()
        assertEquals(listOf(47312, 47312), connectPorts.toList())
    }

    @Test fun refusedConnectRetriedTwiceThenSucceeds() = runBlocking {
        writePort()
        val attempts = AtomicInteger()
        val c = client(connect = { p ->
            connectPorts += p
            if (attempts.incrementAndGet() <= 2) throw ConnectException("Connection refused")
            Socket(loopback, server.localPort)
        })
        assertEquals(HelperPing(1, "/x"), c.ping())
        assertEquals(3, attempts.get())
    }

    @Test fun refusedConnectGivesUpAfterTwoRetries() = runBlocking {
        writePort()
        val attempts = AtomicInteger()
        val c = client(connect = { attempts.incrementAndGet(); throw ConnectException("Connection refused") })
        assertNull(c.ping())
        assertEquals(3, attempts.get())
    }

    @Test fun closedImmediatelyIsRetried() = runBlocking {
        writePort()
        val seen = AtomicInteger()
        handler = { req, s -> if (seen.incrementAndGet() > 1) realHelper(req, s) } // first: close with no reply
        assertEquals(HelperPing(1, "/x"), client().ping())
        assertEquals(2, requests.size)
    }

    @Test fun defaultBackoffIs100Then300() {
        assertEquals(listOf(100L, 300L), HelperClient.DEFAULT_RETRY_DELAYS_MS)
        assertEquals(500L, HelperClient.DEFAULT_IMMEDIATE_CLOSE_WINDOW_MS)
    }

    /** A compile the helper accepted and then died on (no output yet) must never be resent. */
    @Test fun closeAfterTheWindowIsNotRetried() = runBlocking {
        writePort()
        val c = client()
        c.ping()
        handler = { _, _ -> Thread.sleep(800) } // hold well past the 300 ms window, then close with no reply
        try {
            c.shell("pm compile -m speed -f com.nuvio.tv").toList()
            fail("expected AdbConnectionLost")
        } catch (_: AdbConnectionLost) {
        }
        Thread.sleep(500) // a (wrong) retry after 1 ms backoff would have arrived by now
        assertEquals(listOf("pm compile -m speed -f com.nuvio.tv"), requests.drop(1))
    }

    @Test fun resetAfterTheWindowIsNotRetried() = runBlocking {
        writePort()
        val c = client()
        c.ping()
        handler = { _, s -> Thread.sleep(800); s.setSoLinger(true, 0) } // RST on close
        try {
            c.shell("pm compile -m speed -f com.nuvio.tv").toList()
            fail("expected AdbConnectionLost")
        } catch (_: AdbConnectionLost) {
        }
        Thread.sleep(500)
        assertEquals(1, requests.drop(1).size)
    }

    @Test fun resetImmediatelyIsRetried() = runBlocking {
        writePort()
        val seen = AtomicInteger()
        handler = { req, s -> if (seen.incrementAndGet() > 1) realHelper(req, s) else s.setSoLinger(true, 0) }
        assertEquals(HelperPing(1, "/x"), client().ping())
        assertEquals(2, requests.size)
    }

    @Test fun lateCloseOfAPingIsNotRetriedEither() = runBlocking {
        writePort()
        handler = { _, _ -> Thread.sleep(800) }
        assertNull(client().ping())
        Thread.sleep(500)
        assertEquals(1, requests.size)
    }

    @Test fun cancellingTheCollectorClosesTheSocket() = runBlocking {
        writePort()
        val c = client()
        c.ping()
        val closed = CountDownLatch(1)
        handler = { _, s ->
            reply(s, "first line")
            // Block until the client goes away: read() returns -1 or throws once it closes. A read timeout
            // means the client never closed, so it does not count.
            try {
                s.getInputStream().read()
                closed.countDown()
            } catch (_: SocketTimeoutException) {
            } catch (_: Exception) {
                closed.countDown()
            }
        }
        val first = withTimeout(5_000) { c.shell("dumpsys package dexopt").onEach { }.first() }
        assertEquals(ShellEvent.Line("first line"), first)
        assertTrue("client socket not closed", closed.await(5, TimeUnit.SECONDS))
    }

    private companion object {
        const val SERVER_READ_TIMEOUT_MS = 8_000
    }
}
