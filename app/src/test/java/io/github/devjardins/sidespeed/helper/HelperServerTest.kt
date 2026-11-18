package io.github.devjardins.sidespeed.helper

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import java.io.ByteArrayInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class HelperServerTest {
    /** Every test runs on its own thread and fails after 10 s instead of hanging the build. */
    @get:Rule val timeout: Timeout = Timeout.seconds(10)

    private val allowed = 10255
    private val loopback = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
    private val server = ServerSocket(0, 16, loopback)
    private val logs: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val ran: MutableList<List<String>> = Collections.synchronizedList(mutableListOf())
    private val bringBackCalls = AtomicInteger()
    private val acks: MutableList<String> = Collections.synchronizedList(mutableListOf())

    @After fun tearDown() = server.close()

    private fun start(
        uid: Int? = allowed,
        maxConnections: Int = 4,
        requestTimeoutMs: Int = 2_000,
        unreadAtCallerCheck: AtomicInteger? = null,
        writeAck: (String) -> Unit = { acks += it },
        runner: (List<String>, (String) -> Unit) -> Int = { argv, onLine ->
            ran += argv
            onLine("Success")
            0
        },
        installRunner: (List<String>, java.io.InputStream, Long, (String) -> Unit) -> Int =
            { _, _, _, _ -> HelperProtocol.EXIT_HELPER_ERROR },
        bringBack: () -> Int = { bringBackCalls.incrementAndGet(); 0 },
        installGate: InstallGate = InstallGate(),
    ) {
        val s = HelperServer(
            server = server,
            allowedUid = allowed,
            callerUid = { sock ->
                if (unreadAtCallerCheck != null) {
                    Thread.sleep(200) // let the client's request arrive
                    unreadAtCallerCheck.set(sock.getInputStream().available())
                }
                uid
            },
            pong = { HelperProtocol.pong(2000, "/data/app/x/base.apk") },
            runner = runner,
            installRunner = installRunner,
            bringBack = bringBack,
            installGate = installGate,
            writeAck = writeAck,
            log = { logs += it },
            maxConnections = maxConnections,
            requestTimeoutMs = requestTimeoutMs,
        )
        thread(isDaemon = true) { s.serve() }
    }

    /**
     * Sends [request] (if any) and returns the lines received until the server closes the connection. A reset
     * counts as the end: the server closes refused connections with the client's bytes unread, which is a RST.
     */
    private fun exchange(request: String?, readTimeoutMs: Int = 5_000): List<String> =
        Socket(loopback, server.localPort).use { c ->
            c.soTimeout = readTimeoutMs
            val lines = mutableListOf<String>()
            try {
                if (request != null) {
                    c.getOutputStream().write(request.toByteArray(Charsets.ISO_8859_1))
                    c.getOutputStream().flush()
                }
                val r = c.getInputStream().bufferedReader()
                while (true) lines += r.readLine() ?: break
            } catch (_: SocketException) {
            }
            lines
        }

    @Test fun pingFromSideSpeed() {
        start()
        assertEquals(listOf("pong v=1 uid=2000 apk=/data/app/x/base.apk", "__END__:0"), exchange("ping\n"))
    }

    @Test fun plainPingWritesNoAck() {
        start()
        exchange("ping\n")
        assertTrue(acks.isEmpty())
    }

    @Test fun noncePingWritesAckBeforeReplying() {
        val nonce = "abcdefghijklmnop1234"
        start(writeAck = { acks += it; Thread.sleep(100) })
        assertEquals(listOf("pong v=1 uid=2000 apk=/data/app/x/base.apk", "__END__:0"), exchange("ping $nonce\n"))
        assertEquals(listOf(nonce), acks.toList())
        assertTrue(ran.isEmpty())
    }

    @Test fun ackFailureIsAHelperErrorWithoutPong() {
        start(writeAck = { throw java.io.IOException("read-only") })
        val lines = exchange("ping abcdefghijklmnop1234\n")
        assertEquals("__END__:125", lines.last())
        assertFalse(lines.toString(), lines.any { it.startsWith("pong") })
    }

    @Test fun badNoncePingRefused() {
        start()
        assertEquals(listOf("error: request not allowed", "__END__:126"), exchange("ping short\n"))
        assertEquals("__END__:126", exchange("ping " + "a".repeat(65) + "\n").last())
        assertTrue(acks.isEmpty())
    }

    @Test fun pingWithoutNewlineEndedByEof() {
        start()
        Socket(loopback, server.localPort).use { c ->
            c.getOutputStream().write("ping".toByteArray())
            c.shutdownOutput()
            assertEquals("__END__:0", c.getInputStream().bufferedReader().readLines().last())
        }
    }

    @Test fun otherUidRefusedWithNoOutputAndNothingRun() {
        start(uid = 2000)
        assertEquals(emptyList<String>(), exchange("pm compile -m speed -f com.nuvio.tv\n"))
        assertTrue(ran.isEmpty())
        assertTrue(logs.toString(), logs.any { it.startsWith("REFUSED connection") && it.contains("uid=2000") })
    }

    @Test fun unknownUidRefused() {
        start(uid = null)
        assertEquals(emptyList<String>(), exchange("ping\n"))
        assertTrue(logs.any { it.contains("uid=unknown") })
    }

    @Test fun callerCheckedBeforeRequestRead() {
        // All of the client's bytes are still unread when the caller check runs.
        val unread = AtomicInteger(-1)
        start(unreadAtCallerCheck = unread)
        assertEquals("__END__:0", exchange("ping\n").last())
        assertEquals(5, unread.get())
    }

    @Test fun allowedCommandRunsExactArgv() {
        start()
        assertEquals(listOf("Success", "__END__:0"), exchange("pm compile -m speed -f com.nuvio.tv\n"))
        assertEquals(listOf(listOf("/system/bin/pm", "compile", "-m", "speed", "-f", "com.nuvio.tv")), ran.toList())
    }

    @Test fun commandExitCodeIsPassedThrough() {
        start(runner = { _, onLine -> onLine("Failure: x"); 1 })
        assertEquals(listOf("Failure: x", "__END__:1"), exchange("dumpsys package dexopt\n"))
    }

    @Test fun disallowedRequestRefused() {
        start()
        assertEquals(listOf("error: request not allowed", "__END__:126"), exchange("sh -c id\n"))
        assertEquals(listOf("error: request not allowed", "__END__:126"), exchange("pm compile -m speed -f com.foo;reboot\n"))
        assertTrue(ran.isEmpty())
    }

    @Test fun overlongAndNonAsciiRequestsRefused() {
        start()
        assertEquals("__END__:126", exchange("dumpsys package com." + "a".repeat(600) + "\n").last())
        assertEquals("__END__:126", exchange("pingé\n").last())
        assertEquals("__END__:126", exchange("ping\r\n").last())
        assertEquals("__END__:126", exchange("\n").last())
        assertTrue(ran.isEmpty())
    }

    @Test fun silentClientTimesOut() {
        start(requestTimeoutMs = 200)
        assertEquals(listOf("error: bad request", "__END__:126"), exchange(null))
    }

    @Test fun runnerExceptionReported() {
        start(runner = { _, _ -> throw java.io.IOException("boom") })
        assertEquals("__END__:125", exchange("dumpsys package dexopt\n").last())
    }

    @Test fun connectionsBeyondCapRefused() {
        val release = CountDownLatch(1)
        val running = CountDownLatch(1)
        start(maxConnections = 1, runner = { _, _ ->
            running.countDown()
            release.await(10, TimeUnit.SECONDS)
            0
        })
        val first = thread { exchange("dumpsys package dexopt\n") }
        assertTrue(running.await(5, TimeUnit.SECONDS))
        assertEquals(emptyList<String>(), exchange("ping\n"))
        assertTrue(logs.any { it.contains("connections already open") })
        release.countDown()
        first.join(5_000)
        // Slot is free again.
        assertEquals("__END__:0", exchange("ping\n").last())
    }

    @Test fun readRequestLineStopsAtNewline() {
        val input = ByteArrayInputStream("ping\nrest".toByteArray())
        assertEquals("ping", HelperServer.readRequestLine(input))
        assertEquals('r'.code, input.read())
        assertNull(HelperServer.readRequestLine(ByteArrayInputStream("a\tb\n".toByteArray())))
        assertEquals("x".repeat(512), HelperServer.readRequestLine(ByteArrayInputStream("x".repeat(512).toByteArray())))
        assertNull(HelperServer.readRequestLine(ByteArrayInputStream("x".repeat(513).toByteArray())))
    }

    @Test fun server_install_self_streams_exact_bytes_then_brings_back() {
        val received = java.io.ByteArrayOutputStream()
        var seenArgv: List<String>? = null
        var seenSize = -1L
        start(
            installRunner = { argv, stdin, size, onLine ->
                seenArgv = argv
                seenSize = size
                stdin.copyTo(received)
                onLine("Success")
                0
            },
        )
        val lines = exchange("install-self 5\nhelloXYZ")
        assertEquals(
            listOf("/system/bin/cmd", "package", "install", "-r",
                "--pkg", "io.github.devjardins.sidespeed", "-S", "5"),
            seenArgv,
        )
        assertEquals(5L, seenSize)
        assertEquals("hello", received.toByteArray().toString(Charsets.UTF_8))
        assertEquals(1, bringBackCalls.get())
        assertEquals("__END__:0", lines.last())
    }

    @Test fun server_install_self_short_body_kills_and_skips_bring_back() {
        var gotBytes = -1
        start(
            installRunner = { _, stdin, _, _ ->
                gotBytes = stdin.readBytes().size
                HelperProtocol.EXIT_HELPER_ERROR
            },
        )
        val lines = Socket(loopback, server.localPort).use { c ->
            c.soTimeout = 5_000
            c.getOutputStream().write("install-self 5\nhel".toByteArray())
            c.getOutputStream().flush()
            c.shutdownOutput()
            c.getInputStream().bufferedReader().readLines()
        }
        assertEquals(3, gotBytes)
        assertEquals("__END__:125", lines.last())
        assertEquals(0, bringBackCalls.get())
    }

    @Test fun server_install_self_continues_after_client_disconnect() {
        val finished = CountDownLatch(1)
        var received = byteArrayOf()
        start(
            installRunner = { _, stdin, _, _ ->
                received = stdin.readBytes()
                Thread.sleep(200) // install still running while the client is long gone
                finished.countDown()
                0
            },
        )
        Socket(loopback, server.localPort).use { c ->
            c.getOutputStream().write("install-self 5\nhello".toByteArray())
            c.getOutputStream().flush()
        }
        assertTrue(finished.await(5, TimeUnit.SECONDS))
        assertEquals("hello", received.toString(Charsets.UTF_8))
        val deadline = System.currentTimeMillis() + 5_000
        while (bringBackCalls.get() == 0 && System.currentTimeMillis() < deadline) Thread.sleep(50)
        assertEquals(1, bringBackCalls.get())
    }

    @Test fun install_gate_defers_await_idle_until_end() {
        val gate = InstallGate()
        gate.begin()
        var released = false
        val t = thread { gate.awaitIdle(); released = true }
        Thread.sleep(300)
        assertFalse(released)
        gate.end()
        t.join(5_000)
        assertTrue(released)
    }

    @Test fun server_install_self_refused_for_wrong_uid() {
        var invoked = false
        start(
            uid = 2000,
            installRunner = { _, _, _, _ -> invoked = true; 0 },
        )
        assertEquals(emptyList<String>(), exchange("install-self 5\nhello"))
        assertFalse(invoked)
        assertEquals(0, bringBackCalls.get())
    }

    @Test fun loopbackCheck() {
        assertTrue(HelperServer.isLoopback127(loopback))
        assertTrue(HelperServer.isLoopback127(InetAddress.getByAddress(ByteArray(10) + byteArrayOf(-1, -1, 127, 0, 0, 1))))
        assertFalse(HelperServer.isLoopback127(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 2))))
        assertFalse(HelperServer.isLoopback127(InetAddress.getByAddress(ByteArray(15) + byteArrayOf(1))))
        assertFalse(HelperServer.isLoopback127(InetAddress.getByAddress(byteArrayOf(192.toByte(), 168.toByte(), 1, 1))))
        assertFalse(HelperServer.isLoopback127(null))
    }
}
