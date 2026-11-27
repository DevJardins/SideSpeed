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
import java.net.SocketTimeoutException
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

    private val nonce = "abcdefghijklmnop1234"

    @After fun tearDown() = server.close()

    private fun start(
        uid: Int? = allowed,
        selfPkg: String = SideSpeedPackages.RELEASE,
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
        bodyTimeoutMs: Int = HelperServer.BODY_TIMEOUT_MS,
        packageCheck: (String) -> Boolean = { false },
    ) {
        val s = HelperServer(
            server = server,
            allowedUid = allowed,
            selfPkg = selfPkg,
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
            bodyTimeoutMs = bodyTimeoutMs,
            writeAck = writeAck,
            log = { logs += it },
            maxConnections = maxConnections,
            requestTimeoutMs = requestTimeoutMs,
            packageCheck = packageCheck,
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
        assertEquals(
            listOf(HelperProtocol.PROVEN, "pong v=2 uid=2000 apk=/data/app/x/base.apk", "__END__:0"),
            exchange("$nonce ping\n"),
        )
    }

    @Test fun everyRequestIsProvenEvenPlainPing() {
        start()
        exchange("$nonce ping\n")
        assertEquals(listOf(nonce), acks.toList())
    }

    @Test fun noncePingWritesAckBeforeReplying() {
        start(writeAck = { acks += it; Thread.sleep(100) })
        assertEquals(
            listOf(HelperProtocol.PROVEN, "pong v=2 uid=2000 apk=/data/app/x/base.apk", "__END__:0"),
            exchange("$nonce ping\n"),
        )
        assertEquals(listOf(nonce), acks.toList())
        assertTrue(ran.isEmpty())
    }

    @Test fun ackFailureIsAHelperErrorWithoutProof() {
        start(writeAck = { throw java.io.IOException("read-only") })
        val lines = exchange("$nonce ping\n")
        assertTrue(lines.first(), lines.first().startsWith("error:"))
        assertEquals("__END__:125", lines.last())
        assertFalse(lines.toString(), lines.any { it == HelperProtocol.PROVEN })
        assertFalse(lines.toString(), lines.any { it.startsWith("pong") })
    }

    @Test fun missingNonceRefused() {
        start()
        assertEquals(listOf("error: request not allowed", "__END__:126"), exchange("ping\n"))
        assertTrue(acks.isEmpty())
        assertTrue(logs.toString(), logs.any { it.startsWith("REFUSED") && it.contains("nonce") })
    }

    @Test fun badNonceRefused() {
        start()
        assertEquals(listOf("error: request not allowed", "__END__:126"), exchange("short ping\n"))
        assertEquals("__END__:126", exchange("a".repeat(65) + " ping\n").last())
        assertEquals("__END__:126", exchange("$nonce \n").last())
        assertTrue(acks.isEmpty())
        assertTrue(logs.toString(), logs.any { it.startsWith("REFUSED") && it.contains("nonce") })
        assertTrue(ran.isEmpty())
    }

    @Test fun pingWithoutNewlineEndedByEof() {
        start()
        Socket(loopback, server.localPort).use { c ->
            c.getOutputStream().write("$nonce ping".toByteArray())
            c.shutdownOutput()
            val lines = c.getInputStream().bufferedReader().readLines()
            assertEquals(HelperProtocol.PROVEN, lines.first())
            assertEquals("__END__:0", lines.last())
        }
    }

    @Test fun otherUidRefusedWithNoOutputAndNothingRun() {
        start(uid = 2000)
        assertEquals(emptyList<String>(), exchange("$nonce pm compile -m speed -f com.nuvio.tv\n"))
        assertTrue(ran.isEmpty())
        assertTrue(logs.toString(), logs.any { it.startsWith("REFUSED connection") && it.contains("uid=2000") })
    }

    @Test fun unknownUidRefused() {
        start(uid = null)
        assertEquals(emptyList<String>(), exchange("$nonce ping\n"))
        assertTrue(logs.any { it.contains("uid=unknown") })
    }

    @Test fun callerCheckedBeforeRequestRead() {
        // All of the client's bytes are still unread when the caller check runs.
        val unread = AtomicInteger(-1)
        start(unreadAtCallerCheck = unread)
        assertEquals("__END__:0", exchange("$nonce ping\n").last())
        assertEquals("$nonce ping\n".length, unread.get())
    }

    @Test fun allowedCommandRunsExactArgvAfterProof() {
        start()
        assertEquals(
            listOf(HelperProtocol.PROVEN, "Success", "__END__:0"),
            exchange("$nonce pm compile -m speed -f com.nuvio.tv\n"),
        )
        assertEquals(listOf(listOf("/system/bin/pm", "compile", "-m", "speed", "-f", "com.nuvio.tv")), ran.toList())
        assertEquals(listOf(nonce), acks.toList())
    }

    @Test fun commandExitCodeIsPassedThrough() {
        start(runner = { _, onLine -> onLine("Failure: x"); 1 })
        assertEquals(
            listOf(HelperProtocol.PROVEN, "Failure: x", "__END__:1"),
            exchange("$nonce dumpsys package dexopt\n"),
        )
    }

    @Test fun disallowedRequestRefused() {
        start()
        assertEquals(
            listOf(HelperProtocol.PROVEN, "error: request not allowed", "__END__:126"),
            exchange("$nonce sh -c id\n"),
        )
        assertEquals("__END__:126", exchange("$nonce pm compile -m speed -f com.foo;reboot\n").last())
        assertTrue(ran.isEmpty())
    }

    @Test fun refusedRequestLoggedSanitized() {
        start()
        assertEquals("__END__:126", exchange("$nonce sh -c id\n").last())
        assertTrue(
            logs.toString(),
            logs.any { it == "REFUSED request from uid=$allowed: 'sh -c id'" },
        )
        val big = "y".repeat(200)
        assertEquals("__END__:126", exchange("$nonce $big\n").last())
        assertTrue(
            logs.toString(),
            logs.any { it == "REFUSED request from uid=$allowed: '${"y".repeat(80)}…(200 bytes)'" },
        )
    }

    @Test fun overlongAndNonAsciiRequestsRefused() {
        start()
        assertEquals("__END__:126", exchange("$nonce dumpsys package com." + "a".repeat(600) + "\n").last())
        assertEquals("__END__:126", exchange("pingé\n").last())
        assertEquals("__END__:126", exchange("ping\r\n").last())
        assertEquals("__END__:126", exchange("\n").last())
        assertTrue(ran.isEmpty())
    }

    @Test fun concurrentNoncesAreProvenIndependently() {
        start()
        val nonces = listOf("firstnonce12345678", "secondnonce1234567")
        val results = Collections.synchronizedMap(mutableMapOf<String, List<String>>())
        val ready = CountDownLatch(1)
        val done = CountDownLatch(nonces.size)
        nonces.forEach { n ->
            thread(isDaemon = true) {
                try {
                    ready.await(5, TimeUnit.SECONDS)
                    results[n] = exchange("$n ping\n")
                } finally {
                    done.countDown()
                }
            }
        }
        ready.countDown()
        assertTrue(done.await(5, TimeUnit.SECONDS))
        nonces.forEach { n ->
            val lines = results[n]!!
            assertEquals(HelperProtocol.PROVEN, lines.first())
            assertTrue(lines.toString(), lines.any { it.startsWith("pong") })
        }
        assertEquals(nonces.sorted(), acks.toList().sorted())
    }

    @Test fun silentClientTimesOut() {
        start(requestTimeoutMs = 200)
        assertEquals(listOf("error: bad request", "__END__:126"), exchange(null))
    }

    @Test fun runnerExceptionReported() {
        start(runner = { _, _ -> throw java.io.IOException("boom") })
        val lines = exchange("$nonce dumpsys package dexopt\n")
        assertEquals(HelperProtocol.PROVEN, lines.first())
        assertEquals("__END__:125", lines.last())
    }

    @Test fun connectionsBeyondCapRefused() {
        val release = CountDownLatch(1)
        val running = CountDownLatch(1)
        start(maxConnections = 1, runner = { _, _ ->
            running.countDown()
            release.await(10, TimeUnit.SECONDS)
            0
        })
        val first = thread { exchange("$nonce dumpsys package dexopt\n") }
        assertTrue(running.await(5, TimeUnit.SECONDS))
        assertEquals(emptyList<String>(), exchange("$nonce ping\n"))
        assertTrue(logs.any { it.contains("connections already open") })
        release.countDown()
        first.join(5_000)
        // Slot is free again.
        assertEquals("__END__:0", exchange("$nonce ping\n").last())
    }

    @Test fun readRequestLineStopsAtNewline() {
        val input = ByteArrayInputStream("ping\nrest".toByteArray())
        assertEquals("ping", HelperServer.readRequestLine(input))
        assertEquals('r'.code, input.read())
        assertNull(HelperServer.readRequestLine(ByteArrayInputStream("a\tb\n".toByteArray())))
        assertEquals(
            "x".repeat(HelperProtocol.MAX_REQUEST_LINE_CHARS),
            HelperServer.readRequestLine(ByteArrayInputStream("x".repeat(HelperProtocol.MAX_REQUEST_LINE_CHARS).toByteArray())),
        )
        assertNull(HelperServer.readRequestLine(ByteArrayInputStream("x".repeat(HelperProtocol.MAX_REQUEST_LINE_CHARS + 1).toByteArray())))
    }

    @Test fun readRequestLineWithSmallCapKeepsOldLimit() {
        assertEquals(
            "x".repeat(HelperProtocol.MAX_REQUEST_CHARS),
            HelperServer.readRequestLine(
                ByteArrayInputStream("x".repeat(HelperProtocol.MAX_REQUEST_CHARS).toByteArray()),
                HelperProtocol.MAX_REQUEST_CHARS,
            ),
        )
        assertNull(
            HelperServer.readRequestLine(
                ByteArrayInputStream("x".repeat(HelperProtocol.MAX_REQUEST_CHARS + 1).toByteArray()),
                HelperProtocol.MAX_REQUEST_CHARS,
            ),
        )
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
        val lines = exchange("$nonce install-self 5\nhelloXYZ")
        assertEquals(HelperProtocol.PROVEN, lines.first())
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

    @Test fun server_install_self_uses_debug_self_pkg() {
        var seenArgv: List<String>? = null
        start(
            selfPkg = SideSpeedPackages.DEBUG,
            installRunner = { argv, stdin, _, onLine ->
                seenArgv = argv
                stdin.readBytes()
                onLine("Success")
                0
            },
        )
        val lines = exchange("$nonce install-self 5\nhello")
        assertEquals(HelperProtocol.PROVEN, lines.first())
        assertEquals(
            listOf(
                "/system/bin/cmd", "package", "install", "-r",
                "--pkg", SideSpeedPackages.DEBUG, "-S", "5",
            ),
            seenArgv,
        )
        assertEquals("__END__:0", lines.last())
    }

    @Test fun server_bring_back_uses_debug_self_pkg() {
        start(selfPkg = SideSpeedPackages.DEBUG)
        val lines = exchange("$nonce bring-back\n")
        assertEquals(HelperProtocol.PROVEN, lines.first())
        assertEquals(
            listOf(
                listOf(
                    "/system/bin/am", "start",
                    "--activity-clear-top", "--activity-single-top",
                    "-n", "${SideSpeedPackages.DEBUG}/${SideSpeedPackages.MAIN_ACTIVITY}",
                ),
            ),
            ran.toList(),
        )
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
            c.getOutputStream().write("$nonce install-self 5\nhel".toByteArray())
            c.getOutputStream().flush()
            c.shutdownOutput()
            c.getInputStream().bufferedReader().readLines()
        }
        assertEquals(HelperProtocol.PROVEN, lines.first())
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
            c.getOutputStream().write("$nonce install-self 5\nhello".toByteArray())
            c.getOutputStream().flush()
        }
        assertTrue(finished.await(5, TimeUnit.SECONDS))
        assertEquals("hello", received.toString(Charsets.UTF_8))
        val deadline = System.currentTimeMillis() + 5_000
        while (bringBackCalls.get() == 0 && System.currentTimeMillis() < deadline) Thread.sleep(50)
        assertEquals(1, bringBackCalls.get())
    }

    @Test fun install_self_stalled_body_times_out_and_releases_gate() {
        val gate = InstallGate()
        var runnerSaw: Throwable? = null
        start(
            installRunner = { _, stdin, _, _ ->
                try {
                    stdin.readBytes()
                } catch (t: Throwable) {
                    runnerSaw = t
                    throw t
                }
                0
            },
            installGate = gate,
            bodyTimeoutMs = 300,
        )
        val lines = Socket(loopback, server.localPort).use { c ->
            c.soTimeout = 5_000
            c.getOutputStream().write("$nonce install-self 5\nhe".toByteArray())
            c.getOutputStream().flush()
            // Stay open but send nothing more: the server must time out the body.
            c.getInputStream().bufferedReader().readLines()
        }
        assertTrue(runnerSaw is SocketTimeoutException)
        assertEquals("__END__:125", lines.last())
        assertEquals(0, bringBackCalls.get())
        val waiter = thread { gate.awaitIdle() }
        waiter.join(5_000)
        assertFalse(waiter.isAlive)
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

    @Test fun install_gate_await_idle_deadline_gives_up() {
        val gate = InstallGate()
        gate.begin()
        val start = System.currentTimeMillis()
        assertFalse(gate.awaitIdle(300))
        assertTrue(System.currentTimeMillis() - start < 5_000)
        gate.end()
        assertTrue(gate.awaitIdle(5_000))
    }

    @Test fun install_gate_await_idle_returns_at_once_when_idle() {
        assertTrue(InstallGate().awaitIdle(5_000))
    }

    /** A child that never exits until forcibly destroyed, like a hung `cmd package install`. */
    private class HungProcess : Process() {
        @Volatile var forciblyDestroyed = false
        override fun getOutputStream(): java.io.OutputStream = java.io.ByteArrayOutputStream()
        override fun getInputStream(): java.io.InputStream = java.io.ByteArrayInputStream(ByteArray(0))
        override fun getErrorStream(): java.io.InputStream = java.io.ByteArrayInputStream(ByteArray(0))
        override fun waitFor(): Int {
            while (!forciblyDestroyed) Thread.sleep(10)
            return 143
        }

        override fun exitValue(): Int = throw IllegalThreadStateException("still running")
        override fun destroy() {
            forciblyDestroyed = true
        }

        override fun destroyForcibly(): Process {
            forciblyDestroyed = true
            return this
        }
    }

    private class ExitedProcess(private val code: Int) : Process() {
        override fun getOutputStream(): java.io.OutputStream = java.io.ByteArrayOutputStream()
        override fun getInputStream(): java.io.InputStream = java.io.ByteArrayInputStream(ByteArray(0))
        override fun getErrorStream(): java.io.InputStream = java.io.ByteArrayInputStream(ByteArray(0))
        override fun waitFor(): Int = code
        override fun exitValue(): Int = code
        override fun destroy() {}
    }

    @Test fun hung_install_child_is_destroyed_after_the_deadline() {
        val child = HungProcess()
        val destroyed = mutableListOf<Process>()
        val start = System.currentTimeMillis()
        assertNull(waitInstallExit(child, 300) { destroyed += it; it.destroyForcibly() })
        assertTrue(System.currentTimeMillis() - start < 5_000)
        assertEquals(listOf(child), destroyed)
        assertTrue("hung child must be forcibly destroyed", child.forciblyDestroyed)
    }

    @Test fun exited_install_child_reports_its_code() {
        assertEquals(0, waitInstallExit(ExitedProcess(0), 5_000))
        assertEquals(3, waitInstallExit(ExitedProcess(3), 5_000))
    }

    @Test fun install_wait_and_apk_watch_deadlines() {
        assertEquals(180_000L, HelperMain.INSTALL_WAIT_MS)
        assertEquals(240_000L, HelperMain.APK_EXIT_WAIT_MS)
    }

    @Test fun server_install_update_streams_exact_bytes_without_bring_back() {
        val received = java.io.ByteArrayOutputStream()
        var seenArgv: List<String>? = null
        var seenSize = -1L
        val checked = mutableListOf<String>()
        start(
            installRunner = { argv, stdin, size, onLine ->
                seenArgv = argv
                seenSize = size
                stdin.copyTo(received)
                onLine("Success")
                0
            },
            packageCheck = { checked += it; true },
        )
        val lines = exchange("$nonce install-update com.nuvio.tv 5\nhelloXYZ")
        assertEquals(HelperProtocol.PROVEN, lines.first())
        assertEquals(
            listOf("/system/bin/cmd", "package", "install", "-r",
                "--pkg", "com.nuvio.tv", "-S", "5"),
            seenArgv,
        )
        assertEquals(5L, seenSize)
        assertEquals("hello", received.toByteArray().toString(Charsets.UTF_8))
        assertEquals(listOf("com.nuvio.tv"), checked)
        assertEquals(0, bringBackCalls.get())
        assertEquals("__END__:0", lines.last())
        assertTrue(logs.toString(), logs.any { it.contains("run install-update pkg=com.nuvio.tv size=5") })
    }

    @Test fun server_install_update_refused_without_reading_body() {
        var invoked = false
        start(
            installRunner = { _, _, _, _ -> invoked = true; 0 },
            packageCheck = { false },
        )
        val lines = Socket(loopback, server.localPort).use { c ->
            c.soTimeout = 5_000
            c.getOutputStream().write("$nonce install-update com.android.vending 5\nhello".toByteArray())
            c.getOutputStream().flush()
            c.shutdownOutput()
            c.getInputStream().bufferedReader().readLines()
        }
        assertEquals(HelperProtocol.PROVEN, lines.first())
        assertEquals(listOf("error: request not allowed", "__END__:126"), lines.drop(1))
        assertFalse("a refused install-update must not run", invoked)
        assertEquals(0, bringBackCalls.get())
        assertTrue(
            logs.toString(),
            logs.any { it == "REFUSED install-update com.android.vending: not an installed third-party sideloaded app" },
        )
    }

    @Test fun server_install_update_short_body_fails_without_bring_back() {
        var gotBytes = -1
        start(
            installRunner = { _, stdin, _, _ ->
                gotBytes = stdin.readBytes().size
                HelperProtocol.EXIT_HELPER_ERROR
            },
            packageCheck = { true },
        )
        val lines = Socket(loopback, server.localPort).use { c ->
            c.soTimeout = 5_000
            c.getOutputStream().write("$nonce install-update com.nuvio.tv 5\nhel".toByteArray())
            c.getOutputStream().flush()
            c.shutdownOutput()
            c.getInputStream().bufferedReader().readLines()
        }
        assertEquals(HelperProtocol.PROVEN, lines.first())
        assertEquals(3, gotBytes)
        assertEquals("__END__:125", lines.last())
        assertEquals(0, bringBackCalls.get())
    }

    @Test fun server_install_update_refuses_self_package_and_oversize() {
        var invoked = false
        val checked = mutableListOf<String>()
        start(
            installRunner = { _, _, _, _ -> invoked = true; 0 },
            packageCheck = { checked += it; true },
        )
        assertEquals(
            "__END__:126",
            exchange("$nonce install-update io.github.devjardins.sidespeed 10\n0123456789").last(),
        )
        assertEquals(
            "__END__:126",
            exchange("$nonce install-update io.github.devjardins.sidespeed.debug 10\n0123456789").last(),
        )
        assertEquals(
            "__END__:126",
            exchange("$nonce install-update com.nuvio.tv 209715201\n").last(),
        )
        assertFalse(invoked)
        assertTrue("the allowlist refuses before any check: $checked", checked.isEmpty())
        assertTrue(ran.isEmpty())
    }

    @Test fun server_install_update_refused_for_wrong_uid() {
        var invoked = false
        start(
            uid = 2000,
            installRunner = { _, _, _, _ -> invoked = true; 0 },
            packageCheck = { true },
        )
        assertEquals(emptyList<String>(), exchange("$nonce install-update com.nuvio.tv 5\nhello"))
        assertFalse(invoked)
        assertEquals(0, bringBackCalls.get())
    }

    @Test fun server_install_self_refused_for_wrong_uid() {
        var invoked = false
        start(
            uid = 2000,
            installRunner = { _, _, _, _ -> invoked = true; 0 },
        )
        assertEquals(emptyList<String>(), exchange("$nonce install-self 5\nhello"))
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
