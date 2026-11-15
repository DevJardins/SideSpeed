package io.github.devjardins.sidespeed.helper

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
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
    private val allowed = 10255
    private val loopback = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
    private val server = ServerSocket(0, 16, loopback)
    private val logs: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val ran: MutableList<List<String>> = Collections.synchronizedList(mutableListOf())

    @After fun tearDown() = server.close()

    private fun start(
        uid: Int? = allowed,
        maxConnections: Int = 4,
        requestTimeoutMs: Int = 2_000,
        unreadAtCallerCheck: AtomicInteger? = null,
        runner: (List<String>, (String) -> Unit) -> Int = { argv, onLine ->
            ran += argv
            onLine("Success")
            0
        },
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

    @Test fun loopbackCheck() {
        assertTrue(HelperServer.isLoopback127(loopback))
        assertTrue(HelperServer.isLoopback127(InetAddress.getByAddress(ByteArray(10) + byteArrayOf(-1, -1, 127, 0, 0, 1))))
        assertFalse(HelperServer.isLoopback127(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 2))))
        assertFalse(HelperServer.isLoopback127(InetAddress.getByAddress(ByteArray(15) + byteArrayOf(1))))
        assertFalse(HelperServer.isLoopback127(InetAddress.getByAddress(byteArrayOf(192.toByte(), 168.toByte(), 1, 1))))
        assertFalse(HelperServer.isLoopback127(null))
    }
}
