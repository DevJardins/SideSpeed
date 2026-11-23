package io.github.devjardins.sidespeed.helper

import io.github.devjardins.sidespeed.adb.AdbConnectionLost
import io.github.devjardins.sidespeed.adb.ShellEvent
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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
    @get:Rule val timeout: Timeout = Timeout.seconds(60)

    private val loopback = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
    private val server = ServerSocket(0, 50, loopback).apply { soTimeout = 1_000 }
    private val requests: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val connectPorts: MutableList<Int> = Collections.synchronizedList(mutableListOf())
    private val logs: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val portFile by lazy { tmp.root.resolve("sidespeed_helper.port") }
    private val ackDir by lazy { tmp.root.resolve("sidespeed_helper.acks") }

    /** What the fake helper does with each request; the default behaves like the real helper. */
    @Volatile private var handler: (String, Socket) -> Unit = ::realHelper

    @Volatile private var pong = "pong v=2 uid=2000 apk=/x"

    /** When set, the fake writes this to the nonce's ack file instead of the nonce it was sent. */
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
                        // Byte-by-byte like HelperServer.readRequestLine, so a request with a body
                        // (install-self) leaves the body bytes in the stream for the handler.
                        val req = readLineBytes(it.getInputStream()) ?: return@use
                        requests += req
                        handler(req, it)
                    }
                }
            }
        }
    }

    @After fun tearDown() = server.close()

    /** Speaks protocol v2: per-nonce ack file, then `__PROVEN__`, then the payload. */
    private fun realHelper(req: String, s: Socket) {
        val nonce = req.substringBefore(' ')
        val inner = req.substringAfter(' ', "")
        if (!HelperProtocol.NONCE.matches(nonce) || inner.isEmpty()) {
            reply(s, "error: request not allowed", "__END__:126")
            return
        }
        ackDir.mkdirs()
        ackDir.resolve(nonce).writeText((ackOverride ?: nonce) + "\n")
        when {
            inner == "ping" -> reply(s, HelperProtocol.PROVEN, pong, "__END__:0")
            inner == "dumpsys package dexopt" ->
                reply(s, HelperProtocol.PROVEN, "Dexopt state:", "  [com.nuvio.tv]", "__END__:0")
            inner.startsWith("pm compile") -> reply(s, HelperProtocol.PROVEN, "Failure: x", "__END__:1")
            else -> reply(s, HelperProtocol.PROVEN, "error: request not allowed", "__END__:126")
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
        ackDir = ackDir,
        retryDelaysMs = retryDelaysMs,
        immediateCloseWindowMs = immediateCloseWindowMs,
        log = { logs += it },
    )

    private fun writePort(text: String = "47310\n") = portFile.writeText(text)

    private fun readLineBytes(input: java.io.InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b == -1) return sb.toString().ifEmpty { null }
            if (b == '\n'.code) return sb.toString()
            sb.append(b.toChar())
        }
    }

    @Test fun pingParsesPong() = runBlocking {
        writePort()
        val c = client()
        assertEquals(HelperPing(2, "/x"), c.ping())
        assertEquals(listOf(47310), connectPorts.toList())
        assertTrue(requests.single(), Regex("^[A-Za-z0-9]{32} ping$").matches(requests.single()))
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

    @Test fun pingReturnsNullWhenProvenButAckFileMissing() = runBlocking {
        // Impostor: right first line, but it cannot write into the ack dir.
        writePort()
        handler = { _, s -> reply(s, HelperProtocol.PROVEN, pong, "__END__:0") }
        assertNull(client().ping())
    }

    @Test fun pingReturnsNullWhenImpostorAnswersWithoutProof() = runBlocking {
        // A protocol v1 helper (or an impostor) never sends the proof line.
        writePort()
        handler = { _, s -> reply(s, pong, "__END__:0") }
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
        assertEquals(HelperPing(2, "/x"), client().ping())
    }

    @Test fun versionMismatchGivesNull() = runBlocking {
        writePort()
        pong = "pong v=3 uid=2000 apk=/x"
        assertNull(client().ping())
    }

    @Test fun protocolV1HelperGetsTheRestartPath() = runBlocking {
        // The old helper refuses v2 lines and never proves: ping fails closed (null) so the
        // setup flow restarts the helper, exactly like a version mismatch.
        writePort()
        handler = { req, s ->
            val inner = req.substringAfter(' ', "")
            if (inner == "ping") reply(s, "error: request not allowed", "__END__:126")
            else reply(s, "error: request not allowed", "__END__:126")
        }
        assertNull(client().ping())
    }

    @Test fun refusedPingGivesNull() = runBlocking {
        writePort()
        handler = { _, s -> reply(s, HelperProtocol.PROVEN, "error: request not allowed", "__END__:126") }
        assertNull(client().ping())
    }

    @Test fun apkPathWithTildesAndEquals() = runBlocking {
        writePort()
        val path = "/data/app/~~Ab3_x-Y==/io.github.devjardins.sidespeed-Qz9==/base.apk"
        pong = "pong v=2 uid=2000 apk=$path"
        assertEquals(HelperPing(2, path), client().ping())
    }

    @Test fun parsePong() {
        assertEquals(HelperPing(1, "/x"), HelperPing.parse("pong v=1 uid=2000 apk=/x"))
        assertEquals(HelperPing(3, "/a b=c"), HelperPing.parse("pong v=3 uid=2000 apk=/a b=c"))
        listOf("pong", "pong v=1", "pong v=x uid=2000 apk=/x", "pong v=1 uid=2000 apk=", "ping v=1 uid=2000 apk=/x", "")
            .forEach { assertNull(it, HelperPing.parse(it)) }
    }

    @Test fun isCurrentComparesApkPath() {
        assertTrue(HelperPing(2, "/x").isCurrent("/x"))
        assertFalse(HelperPing(2, "/x").isCurrent("/y"))
    }

    @Test fun shellGivesLinesThenExit() = runBlocking {
        writePort()
        val c = client()
        c.ping()
        assertEquals(
            listOf(ShellEvent.Line("Dexopt state:"), ShellEvent.Line("  [com.nuvio.tv]"), ShellEvent.Exit(0)),
            c.shell("dumpsys package dexopt").toList(),
        )
        assertTrue(requests.last(), Regex("^[A-Za-z0-9]{32} dumpsys package dexopt$").matches(requests.last()))
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
        handler = { req, s ->
            val nonce = req.substringBefore(' ')
            ackDir.resolve(nonce).writeText("$nonce\n")
            reply(s, HelperProtocol.PROVEN, "partial")
        }
        val seen = mutableListOf<ShellEvent>()
        try {
            c.shell("dumpsys package dexopt").collect { seen += it }
            fail("expected AdbConnectionLost")
        } catch (_: AdbConnectionLost) {
        }
        assertEquals(listOf(ShellEvent.Line("partial")), seen)
    }

    @Test fun shellWithoutProofEmitsNothingAndThrows() = runBlocking {
        writePort()
        val c = client()
        c.ping()
        // The helper died after the ping and an impostor took the port: no proof line.
        handler = { _, s -> reply(s, "Dexopt state:", "__END__:0") }
        val seen = mutableListOf<ShellEvent>()
        try {
            c.shell("dumpsys package dexopt").collect { seen += it }
            fail("expected AdbConnectionLost")
        } catch (_: AdbConnectionLost) {
        }
        assertTrue("unproven output must never reach the collector: $seen", seen.isEmpty())
    }

    @Test fun shellProvenWithoutAckEmitsNothingAndThrows() = runBlocking {
        writePort()
        val c = client()
        c.ping()
        handler = { _, s -> reply(s, HelperProtocol.PROVEN, "Dexopt state:", "__END__:0") }
        val seen = mutableListOf<ShellEvent>()
        try {
            c.shell("dumpsys package dexopt").collect { seen += it }
            fail("expected AdbConnectionLost")
        } catch (_: AdbConnectionLost) {
        }
        assertTrue(seen.isEmpty())
        // The failed proof clears the port: nothing is sent after it.
        val sent = requests.size
        try {
            c.shell("dumpsys package dexopt").collect { }
            fail("expected AdbConnectionLost")
        } catch (e: AdbConnectionLost) {
            assertTrue(e.message, e.message!!.contains("helper not verified"))
        }
        assertEquals(sent, requests.size)
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
        assertEquals(HelperPing(2, "/x"), c.ping())
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

    @Test fun concurrentShellsProveIndependently() = runBlocking {
        writePort()
        val c = client()
        c.ping()
        val first = async { c.shell("dumpsys package dexopt").toList() }
        val second = async { c.shell("pm compile -m speed -f com.nuvio.tv").toList() }
        val (a, b) = awaitAll(first, second)
        assertTrue(a.contains(ShellEvent.Exit(0)))
        assertEquals(listOf(ShellEvent.Line("Failure: x"), ShellEvent.Exit(1)), b)
        val shellReqs = requests.drop(1)
        assertEquals(2, shellReqs.size)
        assertNotEquals(shellReqs[0].substringBefore(' '), shellReqs[1].substringBefore(' '))
    }

    @Test fun refusedConnectRetriedTwiceThenSucceeds() = runBlocking {
        writePort()
        val attempts = AtomicInteger()
        val c = client(connect = { p ->
            connectPorts += p
            if (attempts.incrementAndGet() <= 2) throw ConnectException("Connection refused")
            Socket(loopback, server.localPort)
        })
        assertEquals(HelperPing(2, "/x"), c.ping())
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
        assertEquals(HelperPing(2, "/x"), client().ping())
        assertEquals(2, requests.size)
    }

    @Test fun defaultBackoffIs100Then300() {
        assertEquals(listOf(100L, 300L), HelperClient.DEFAULT_RETRY_DELAYS_MS)
        assertEquals(500L, HelperClient.DEFAULT_IMMEDIATE_CLOSE_WINDOW_MS)
    }

    @Test fun install_update_sends_header_and_exact_bytes() = runBlocking {
        writePort()
        val c = client()
        c.ping()
        val apk = tmp.newFile("update.apk")
        apk.writeBytes("hello-world".toByteArray())
        val gotHeader = ArrayList<String>()
        val gotBody = ArrayList<Byte>()
        handler = { req, s ->
            if (req.endsWith(" ping")) realHelper(req, s)
            else {
                gotHeader += req
                val size = req.substringAfterLast(' ').toInt()
                val nonce = req.substringBefore(' ')
                ackDir.resolve(nonce).writeText("$nonce\n")
                reply(s, HelperProtocol.PROVEN)
                val body = ByteArray(size)
                var off = 0
                while (off < size) {
                    val n = s.getInputStream().read(body, off, size - off)
                    if (n < 0) break
                    off += n
                }
                gotBody += body.toList()
                reply(s, "Success", "__END__:0")
            }
        }
        assertEquals(InstallOutcome.Started, c.installUpdate("com.nuvio.tv", apk))
        assertEquals(1, gotHeader.size)
        assertTrue(gotHeader.single(), Regex("^[A-Za-z0-9]{32} install-update com\\.nuvio\\.tv 11$").matches(gotHeader.single()))
        assertEquals("hello-world", gotBody.toByteArray().toString(Charsets.UTF_8))
    }

    @Test fun install_update_reports_failed_on_nonzero_end() = runBlocking {
        writePort()
        val c = client()
        c.ping()
        val apk = tmp.newFile("update.apk")
        apk.writeBytes("hello".toByteArray())
        handler = { req, s ->
            if (req.endsWith(" ping")) realHelper(req, s)
            else {
                val size = req.substringAfterLast(' ').toInt()
                val nonce = req.substringBefore(' ')
                ackDir.resolve(nonce).writeText("$nonce\n")
                reply(s, HelperProtocol.PROVEN)
                s.getInputStream().readNBytes(size)
                reply(s, "Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE: foo]", "__END__:1")
            }
        }
        assertEquals(
            InstallOutcome.Failed(1, "Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE: foo]"),
            c.installUpdate("com.nuvio.tv", apk),
        )
    }

    @Test fun install_update_helper_down_when_no_port() = runBlocking {
        writePort("47310")
        assertEquals(InstallOutcome.HelperDown, client().installUpdate("com.nuvio.tv", tmp.newFile("a.apk")))
    }

    @Test fun install_update_impostor_gets_no_body_bytes() = runBlocking {
        writePort()
        val c = client()
        c.ping()
        val bodyBytes = AtomicInteger()
        handler = { req, s ->
            if (req.endsWith(" ping")) realHelper(req, s)
            else {
                reply(s, "Success", "__END__:0")
                try {
                    val buf = ByteArray(4096)
                    while (true) {
                        val n = s.getInputStream().read(buf)
                        if (n < 0) break
                        bodyBytes.addAndGet(n)
                    }
                } catch (_: Exception) {
                }
            }
        }
        val apk = tmp.newFile("update.apk")
        apk.writeBytes("hello".toByteArray())
        assertEquals(InstallOutcome.HelperDown, c.installUpdate("com.nuvio.tv", apk))
        assertEquals("no body byte may reach an unproven server", 0, bodyBytes.get())
        assertEquals(1, requests.count { it.contains("install-update") })
    }

    @Test fun install_update_bare_disconnect_after_body_is_not_success() = runBlocking {
        writePort()
        val c = client()
        c.ping()
        val apk = tmp.newFile("update.apk")
        apk.writeBytes("hello".toByteArray())
        handler = { req, s ->
            if (req.endsWith(" ping")) realHelper(req, s)
            else {
                val size = req.substringAfterLast(' ').toInt()
                val nonce = req.substringBefore(' ')
                ackDir.resolve(nonce).writeText("$nonce\n")
                reply(s, HelperProtocol.PROVEN)
                s.getInputStream().readNBytes(size)
            }
        }
        assertEquals(InstallOutcome.Disconnected, c.installUpdate("com.nuvio.tv", apk))
    }

    @Test fun install_update_empty_apk_is_failed_without_connecting() = runBlocking {
        writePort()
        val c = client()
        c.ping()
        val apk = tmp.newFile("update.apk")
        apk.writeBytes(ByteArray(0))
        val sent = requests.size
        assertEquals(
            InstallOutcome.Failed(HelperProtocol.EXIT_HELPER_ERROR, "empty APK file"),
            c.installUpdate("com.nuvio.tv", apk),
        )
        assertEquals(sent, requests.size)
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
        assertEquals(1, requests.drop(1).size)
        assertTrue(requests.drop(1).single().endsWith("pm compile -m speed -f com.nuvio.tv"))
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
        assertEquals(HelperPing(2, "/x"), client().ping())
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
        handler = { req, s ->
            val nonce = req.substringBefore(' ')
            ackDir.resolve(nonce).writeText("$nonce\n")
            reply(s, HelperProtocol.PROVEN, "first line")
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

    @Test fun install_self_sends_header_and_exact_bytes() = runBlocking {
        writePort()
        val c = client()
        c.ping()
        val apk = tmp.newFile("update.apk")
        apk.writeBytes("hello-world".toByteArray())
        val gotHeader = ArrayList<String>()
        val gotBody = ArrayList<Byte>()
        handler = { req, s ->
            if (req.endsWith(" ping")) realHelper(req, s)
            else {
                gotHeader += req
                val size = req.substringAfterLast(' ').toInt()
                val nonce = req.substringBefore(' ')
                ackDir.resolve(nonce).writeText("$nonce\n")
                reply(s, HelperProtocol.PROVEN)
                val body = ByteArray(size)
                var off = 0
                while (off < size) {
                    val n = s.getInputStream().read(body, off, size - off)
                    if (n < 0) break
                    off += n
                }
                gotBody += body.toList()
                reply(s, "Success", "__END__:0")
            }
        }
        assertEquals(InstallOutcome.Started, c.installSelf(apk))
        assertEquals(1, gotHeader.size)
        assertTrue(gotHeader.single(), Regex("^[A-Za-z0-9]{32} install-self 11$").matches(gotHeader.single()))
        assertEquals("hello-world", gotBody.toByteArray().toString(Charsets.UTF_8))
    }

    @Test fun install_self_reports_failed_on_nonzero_end() = runBlocking {
        writePort()
        val c = client()
        c.ping()
        val apk = tmp.newFile("update.apk")
        apk.writeBytes("hello".toByteArray())
        handler = { req, s ->
            if (req.endsWith(" ping")) realHelper(req, s)
            else {
                val size = req.substringAfterLast(' ').toInt()
                val nonce = req.substringBefore(' ')
                ackDir.resolve(nonce).writeText("$nonce\n")
                reply(s, HelperProtocol.PROVEN)
                s.getInputStream().readNBytes(size)
                reply(s, "Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE: foo]", "__END__:1")
            }
        }
        assertEquals(
            InstallOutcome.Failed(1, "Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE: foo]"),
            c.installSelf(apk),
        )
    }

    @Test fun install_self_helper_down_when_no_port() = runBlocking {
        writePort("47310")
        assertEquals(InstallOutcome.HelperDown, client().installSelf(tmp.newFile("a.apk")))
    }

    @Test fun install_self_impostor_gets_no_body_bytes() = runBlocking {
        writePort()
        val c = client()
        c.ping()
        // Impostor takes the port after the ping: on the install connection it answers
        // without proof, so the client must send zero body bytes.
        val bodyBytes = AtomicInteger()
        handler = { req, s ->
            if (req.endsWith(" ping")) realHelper(req, s)
            else {
                reply(s, "Success", "__END__:0")
                try {
                    val buf = ByteArray(4096)
                    while (true) {
                        val n = s.getInputStream().read(buf)
                        if (n < 0) break
                        bodyBytes.addAndGet(n)
                    }
                } catch (_: Exception) {
                }
            }
        }
        val apk = tmp.newFile("update.apk")
        apk.writeBytes("hello".toByteArray())
        assertEquals(InstallOutcome.HelperDown, c.installSelf(apk))
        assertEquals("no body byte may reach an unproven server", 0, bodyBytes.get())
        // The request line goes out, but the install never starts: exactly one install line, no retry.
        assertEquals(1, requests.count { it.contains("install-self") })
    }

    @Test fun install_self_proven_without_ack_gets_no_body_bytes() = runBlocking {
        writePort()
        val c = client()
        c.ping()
        val bodyBytes = AtomicInteger()
        handler = { req, s ->
            if (req.endsWith(" ping")) realHelper(req, s)
            else {
                reply(s, HelperProtocol.PROVEN, "Success", "__END__:0")
                try {
                    val buf = ByteArray(4096)
                    while (true) {
                        val n = s.getInputStream().read(buf)
                        if (n < 0) break
                        bodyBytes.addAndGet(n)
                    }
                } catch (_: Exception) {
                }
            }
        }
        val apk = tmp.newFile("update.apk")
        apk.writeBytes("hello".toByteArray())
        assertEquals(InstallOutcome.HelperDown, c.installSelf(apk))
        assertEquals("no body byte may reach an unproven server", 0, bodyBytes.get())
    }

    @Test fun install_self_bare_disconnect_after_body_is_not_success() = runBlocking {
        writePort()
        val c = client()
        c.ping()
        val apk = tmp.newFile("update.apk")
        apk.writeBytes("hello".toByteArray())
        handler = { req, s ->
            if (req.endsWith(" ping")) realHelper(req, s)
            else {
                val size = req.substringAfterLast(' ').toInt()
                val nonce = req.substringBefore(' ')
                ackDir.resolve(nonce).writeText("$nonce\n")
                reply(s, HelperProtocol.PROVEN)
                s.getInputStream().readNBytes(size)
                // Impostor reads the body and closes without __END__:0.
            }
        }
        assertEquals(InstallOutcome.Disconnected, c.installSelf(apk))
    }

    private companion object {
        const val SERVER_READ_TIMEOUT_MS = 8_000
    }

    /**
     * Regression: lines read just before the connection drops must reach the collector. With a buffered
     * flowOn hand-off, the upstream failure could cancel the channel and drop the last buffered line
     * (about 3% of runs), losing e.g. the final error output of a compile.
     */
    @Test fun lineReadJustBeforeADropIsNeverLost() = runBlocking {
        writePort()
        var lost = 0
        repeat(150) {
            val c = client()
            c.ping()
            handler = { req, s ->
                val nonce = req.substringBefore(' ')
                ackDir.resolve(nonce).writeText("$nonce\n")
                reply(s, HelperProtocol.PROVEN, "partial")
            }
            val seen = mutableListOf<ShellEvent>()
            try {
                c.shell("dumpsys package dexopt").collect { seen += it }
            } catch (_: AdbConnectionLost) {
            }
            if (seen != listOf(ShellEvent.Line("partial"))) lost++
            handler = ::realHelper
        }
        assertEquals("runs that lost the last line", 0, lost)
    }
}
