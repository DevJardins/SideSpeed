package io.github.devjardins.sidespeed.companion.tv

import io.github.devjardins.sidespeed.adb.AdbConnectionLost
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.EOFException
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class KadbTvConnectorTest {

    /** A scripted link. Blocking behaviours wait until close(), as a real socket read would. */
    private class FakeLink(
        val onConnect: FakeLink.() -> Unit = {},
        val onShell: FakeLink.(String) -> String = { "" },
        val onOpenPackageCmd: FakeLink.(Array<out String>) -> InstallStream = {
            throw UnsupportedInstallStreamException()
        },
    ) : AdbLink {
        val closedLatch = CountDownLatch(1)
        var connected = false
        val shellCommands = mutableListOf<String>()
        val closed get() = closedLatch.count == 0L

        fun blockUntilClosed(): Nothing {
            check(closedLatch.await(10, TimeUnit.SECONDS)) { "never closed" }
            throw IOException("socket closed")
        }

        override fun connect() {
            onConnect()
            connected = true
        }

        override fun shell(command: String): String {
            shellCommands += command
            return onShell(command)
        }

        override fun openPackageCmd(vararg args: String): InstallStream = onOpenPackageCmd(args)

        override val isConnected get() = connected && !closed
        override fun close() = closedLatch.countDown()
    }

    private fun connector(link: FakeLink, authTimeoutMs: Long = 300, commandTimeoutMs: Long = 300) =
        KadbTvConnector(authTimeoutMs, connectTimeoutMs = 100, commandTimeoutMs = commandTimeoutMs) { _, _ -> link }

    @Test
    fun refusedIsUnreachable() = runBlocking {
        val link = FakeLink(onConnect = { throw ConnectException("Connection refused") })
        val e = assertFailsWith<TvUnreachable> { connector(link).open("10.0.0.5") }
        assertIs<ConnectException>(e.cause)
        assertTrue(link.closed)
    }

    @Test
    fun noRouteIsUnreachable() = runBlocking {
        val link = FakeLink(onConnect = { throw NoRouteToHostException() })
        assertFailsWith<TvUnreachable> { connector(link).open("10.0.0.5") }
        Unit
    }

    @Test
    fun kadbConnectTimeoutIsUnreachableNotCancellation() = runBlocking {
        // Kadb's TCP connect uses withTimeout internally, so its timeout escapes as TimeoutCancellationException.
        val link = FakeLink(onConnect = { runBlocking { withTimeout(1) { delay(1_000) } } })
        val e = assertFailsWith<TvUnreachable> { connector(link).open("10.0.0.5") }
        assertIs<java.net.SocketTimeoutException>(e.cause)
        assertIs<TimeoutCancellationException>(e.cause?.cause)
        Unit
    }

    @Test
    fun cancellationExceptionFromTheCallItselfIsNotTreatedAsCancellation() = runBlocking {
        val link = FakeLink(onConnect = { throw CancellationException("Kadb-internal") })
        assertFailsWith<TvUnreachable> { connector(link).open("10.0.0.5") }
        val ok = FakeLink(onShell = { throw CancellationException("Kadb-internal") })
        val session = connector(ok).open("10.0.0.5")
        assertFailsWith<AdbConnectionLost> { session.run("getprop ro.serialno") }
        assertTrue(ok.closed)
    }

    @Test
    fun peerClosingDuringHandshakeIsUnreachable() = runBlocking {
        val link = FakeLink(onConnect = { throw EOFException() })
        assertFailsWith<TvUnreachable> { connector(link).open("10.0.0.5") }
        Unit
    }

    @Test
    fun handshakeWaitingPastAuthTimeoutIsAuthPendingAndClosesTheLink() = runBlocking {
        val link = FakeLink(onConnect = { blockUntilClosed() })
        val started = System.nanoTime()
        assertFailsWith<TvAuthPending> { connector(link, authTimeoutMs = 300).open("10.0.0.5") }
        val tookMs = (System.nanoTime() - started) / 1_000_000
        assertTrue(tookMs in 250..3_000, "took $tookMs ms")
        assertTrue(link.closed)
    }

    @Test
    fun runReturnsTrimmedStdout() = runBlocking {
        val link = FakeLink(onShell = { if (it == "getprop ro.serialno") "G070VM1234\r\n" else "" })
        val session = connector(link).open("10.0.0.5")
        assertEquals("G070VM1234", session.run("getprop ro.serialno"))
        assertEquals("", session.run("pidof sidespeed_helper"))
        assertEquals(listOf("getprop ro.serialno", "pidof sidespeed_helper"), link.shellCommands)
        session.close()
        assertTrue(link.closed)
    }

    @Test
    fun streamFailureAfterConnectIsConnectionLostAndKillsTheSession() = runBlocking {
        val link = FakeLink(onShell = { throw IOException("connection reset") })
        val session = connector(link).open("10.0.0.5")
        val e = assertFailsWith<AdbConnectionLost> { session.run("getprop ro.serialno") }
        assertIs<IOException>(e.cause)
        assertTrue(link.closed)
        assertFailsWith<AdbConnectionLost> { session.run("getprop ro.serialno") }
        assertEquals(1, link.shellCommands.size, "no command is sent on a dead session")
    }

    @Test
    fun droppedTransportIsConnectionLostWithoutReconnecting() = runBlocking {
        val link = FakeLink()
        val session = connector(link).open("10.0.0.5")
        link.connected = false
        assertFailsWith<AdbConnectionLost> { session.run("getprop ro.serialno") }
        assertTrue(link.shellCommands.isEmpty())
    }

    @Test
    fun hungCommandTimesOutAsConnectionLost() = runBlocking {
        val link = FakeLink(onShell = { blockUntilClosed() })
        val session = connector(link, commandTimeoutMs = 200).open("10.0.0.5")
        assertFailsWith<AdbConnectionLost> { session.run("getprop ro.serialno") }
        assertTrue(link.closed)
    }

    @Test
    fun cancellationIsRethrownAndClosesTheLink() = runBlocking {
        val entered = CountDownLatch(1)
        val link = FakeLink(onConnect = { entered.countDown(); blockUntilClosed() })
        val opening = async(Dispatchers.Default) { connector(link, authTimeoutMs = 10_000).open("10.0.0.5") }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        opening.cancel()
        assertFailsWith<CancellationException> { opening.await() }
        assertTrue(link.closedLatch.await(5, TimeUnit.SECONDS))
    }

    @Test
    fun connectTimeoutMustBeShorterThanAuthTimeout() {
        assertFailsWith<IllegalArgumentException> {
            KadbTvConnector(authTimeoutMs = 1_000, connectTimeoutMs = 1_000, commandTimeoutMs = 1_000) { _, _ -> FakeLink() }
        }
    }

    @Test
    fun commandTimeoutMustBeBelowTheSocketReadTimeout() {
        // The read timeout is authTimeoutMs + 5 s; a longer command timeout could never fire.
        assertFailsWith<IllegalArgumentException> {
            KadbTvConnector(authTimeoutMs = 20_000, connectTimeoutMs = 5_000, commandTimeoutMs = 25_000) { _, _ -> FakeLink() }
        }
        KadbTvConnector(authTimeoutMs = 20_000, connectTimeoutMs = 5_000, commandTimeoutMs = 20_000) { _, _ -> FakeLink() }
    }

    @Test
    fun forInstallsBuildsWithLongerCommandTimeoutAndKeepsSessionAliveOnUnsupportedStream() = runBlocking {
        KadbTvConnector.forInstalls(java.io.File("/tmp"))
        val link = FakeLink(onShell = { "SER-1" })
        val session = connector(link).open("10.0.0.5")
        assertFailsWith<UnsupportedInstallStreamException> {
            session.openPackageCmd("install-create", "-r", "-S", "10")
        }
        assertTrue(!link.closed)
        assertEquals("SER-1", session.run("getprop ro.serialno"))
    }

    @Test
    fun installStreamIoFailureKillsKadbTvSession() = runBlocking {
        val failingStream = object : InstallStream {
            override fun write(bytes: ByteArray, offset: Int, length: Int) = throw IOException("reset")
            override fun flush() = Unit
            override fun readAllText(): String = ""
            override fun close() = Unit
        }
        val link = FakeLink(onOpenPackageCmd = { failingStream })
        val session = connector(link).open("10.0.0.5")
        val stream = session.openPackageCmd("install-write", "-S", "4", "1", "base.apk", "-")
        assertFailsWith<AdbConnectionLost> { stream.write(byteArrayOf(1, 2, 3, 4), 0, 4) }
        assertTrue(link.closed)
        assertFailsWith<AdbConnectionLost> { session.run("getprop ro.serialno") }
        Unit
    }
}
