package io.github.devjardins.sidespeed.companion.discovery

import io.github.devjardins.sidespeed.companion.net.Ipv4
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class SocketAdbProbeTest {
    private val loopback = Ipv4.parse("127.0.0.1")!!
    private val probe = SocketAdbProbe()

    private fun header(cmd: Long, magic: Long = cmd xor 0xFFFFFFFFL): ByteArray =
        ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(cmd.toInt()).putInt(0x01000000).putInt(4096).putInt(0).putInt(0).putInt(magic.toInt()).array()

    private val auth = 0x48545541L
    private val cnxn = 0x4e584e43L
    private val stls = 0x534c5453L

    /** Runs a one-connection server; [onClient] gets the socket and the bytes the client sent are returned via [received]. */
    private class Server(val onClient: (Socket) -> Unit) : AutoCloseable {
        val socket = ServerSocket(0, 5, InetAddress.getByName("127.0.0.1"))
        val received = ByteArrayOutputStream()
        val port get() = socket.localPort
        private val worker = thread(isDaemon = true) {
            runCatching { socket.accept().use { c -> c.soTimeout = 2000; onClient(c) } }
        }
        fun finish(): ByteArray { worker.join(4000); return received.toByteArray() }
        override fun close() = socket.close()
    }

    private fun Socket.readHello(sink: ByteArrayOutputStream) {
        val buf = ByteArray(31)
        var n = 0
        while (n < 31) { val r = getInputStream().read(buf, n, 31 - n); if (r < 0) break; n += r }
        sink.write(buf, 0, n)
    }


    private fun run(timeout: Int = 1500, reply: (Socket) -> Unit): Pair<ProbeResult, ByteArray> {
        lateinit var server: Server
        server = Server { c ->
            c.readHello(server.received)
            reply(c)
            // Anything else the client writes (it should write nothing) lands here until it closes.
            runCatching { c.getInputStream().readBytes().let { server.received.write(it) } }
        }
        server.use {
            val result = runBlocking { probe.probe(loopback, server.port, timeout) }
            return result to server.finish()
        }
    }

    @Test fun goldenConnectPacket() {
        val expected = byteArrayOf(
            0x43, 0x4e, 0x58, 0x4e, 0x00, 0x00, 0x00, 0x01, 0x00, 0x10, 0x00, 0x00, 0x07, 0x00, 0x00, 0x00,
            0x32, 0x02, 0x00, 0x00, 0xbc.toByte(), 0xb1.toByte(), 0xa7.toByte(), 0xb1.toByte(),
            0x68, 0x6f, 0x73, 0x74, 0x3a, 0x3a, 0x00,
        )
        assertContentEquals(expected, SocketAdbProbe.connectPacket())
    }

    @Test fun authChallengeIsAdbAuthRequired() {
        val (result, sent) = run { it.getOutputStream().write(header(auth)) }
        assertEquals(ProbeResult.AdbAuthRequired, result)
        assertContentEquals(SocketAdbProbe.connectPacket(), sent) // exactly one packet, nothing more
    }

    @Test fun cnxnBannerIsAdbOpen() {
        assertEquals(ProbeResult.AdbOpen, run { it.getOutputStream().write(header(cnxn)) }.first)
    }

    @Test fun stlsIsAdbTls() {
        assertEquals(ProbeResult.AdbTls, run { it.getOutputStream().write(header(stls)) }.first)
    }

    @Test fun httpGarbageIsNotAdb() {
        val garbage = "HTTP/1.1 400 Bad Request\r\nServer: x\r\n\r\n".toByteArray()
        assertEquals(ProbeResult.NotAdb, run { it.getOutputStream().write(garbage) }.first)
    }

    @Test fun rightCommandWithWrongMagicIsNotAdb() {
        assertEquals(ProbeResult.NotAdb, run { it.getOutputStream().write(header(auth, magic = 0x12345678L)) }.first)
    }

    @Test fun otherAdbCommandIsNotAccepted() {
        val okay = 0x59414b4fL
        assertEquals(ProbeResult.NotAdb, run { it.getOutputStream().write(header(okay)) }.first)
    }

    @Test fun silenceIsNotAdb() {
        assertEquals(ProbeResult.NotAdb, run(timeout = 250) { Thread.sleep(600) }.first)
    }

    @Test fun resetIsNotAdb() {
        val result = run { it.setSoLinger(true, 0); it.close() }.first
        assertEquals(ProbeResult.NotAdb, result)
    }

    @Test fun shortReplyThenCloseIsNotAdb() {
        assertEquals(ProbeResult.NotAdb, run { it.getOutputStream().write(header(cnxn).copyOf(10)); it.close() }.first)
    }

    @Test fun closedPortIsRefused() {
        val port = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
        assertEquals(ProbeResult.Refused, runBlocking { probe.probe(loopback, port, 500) })
    }

    @Test fun cancellationClosesTheSocket() {
        lateinit var server: Server
        server = Server { c -> c.readHello(server.received); Thread.sleep(3000) }
        server.use {
            runBlocking {
                val job = launch { probe.probe(loopback, server.port, 10_000) }
                delay(300)
                val started = System.nanoTime()
                job.cancelAndJoin()
                assertEquals(true, (System.nanoTime() - started) / 1_000_000 < 1000, "cancel returned promptly")
            }
        }
    }
}
