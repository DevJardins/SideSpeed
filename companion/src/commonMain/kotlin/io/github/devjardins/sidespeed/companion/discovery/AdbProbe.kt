package io.github.devjardins.sidespeed.companion.discovery

import io.github.devjardins.sidespeed.companion.net.Ipv4
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.NoRouteToHostException
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.resume

/** What one probe of one address learned. */
enum class ProbeResult {
    /** Speaks ADB and wants authorisation (AUTH): debugging is on. */
    AdbAuthRequired,

    /** Speaks ADB and accepted us without asking (CNXN banner). */
    AdbOpen,

    /** Speaks ADB over TLS (STLS): debugging is on, but this companion can't use it. */
    AdbTls,

    /** Something answered on the port but it isn't ADB (or said nothing, or hung up). */
    NotAdb,

    /** The host is up and the port is closed. */
    Refused,

    /** Nothing answered before the timeout. */
    Timeout,

    /** The network said there is no route or host. */
    Unreachable,

    /** Any other failure. */
    Error,
    ;

    /** A host answered in some way, so something is there. */
    val hostIsLive: Boolean get() = this != Timeout && this != Unreachable && this != Error
    val isAdb: Boolean get() = this == AdbAuthRequired || this == AdbOpen || this == AdbTls
}

/** Checks one address for ADB without ever starting a real ADB session (which would prompt the TV). */
fun interface AdbProbe {
    suspend fun probe(address: Ipv4, port: Int, timeoutMs: Int): ProbeResult
}

/**
 * Sends ONE ADB CNXN packet and reads one 24-byte header. The reply counts only if its magic is the command
 * XOR 0xFFFFFFFF and the command is AUTH, CNXN or STLS. Then it closes: it never answers an AUTH challenge,
 * so no "Allow USB debugging?" prompt is raised on the device. The socket is closed on cancellation.
 */
class SocketAdbProbe(dispatcher: CoroutineDispatcher? = null) : AdbProbe {
    private val dispatcher = dispatcher ?: shared

    override suspend fun probe(address: Ipv4, port: Int, timeoutMs: Int): ProbeResult = withContext(dispatcher) {
        val socket = Socket()
        suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation { runCatching { socket.close() } }
            cont.resume(blockingProbe(socket, address, port, timeoutMs))
        }
    }

    private fun blockingProbe(socket: Socket, address: Ipv4, port: Int, timeoutMs: Int): ProbeResult = try {
        socket.use {
            it.tcpNoDelay = true
            it.connect(InetSocketAddress(address.toString(), port), timeoutMs)
            it.soTimeout = timeoutMs
            it.getOutputStream().apply { write(connectPacket()); flush() }
            readVerdict(it)
        }
    } catch (e: SocketTimeoutException) {
        ProbeResult.Timeout
    } catch (e: ConnectException) {
        if (e.message?.contains("refused", ignoreCase = true) == true) ProbeResult.Refused else ProbeResult.Unreachable
    } catch (e: NoRouteToHostException) {
        ProbeResult.Unreachable
    } catch (e: IOException) {
        // After connecting: a reset or early close means something is there that isn't ADB.
        if (socket.isConnected) ProbeResult.NotAdb else ProbeResult.Error
    }

    private fun readVerdict(socket: Socket): ProbeResult {
        val buf = ByteArray(24)
        var n = 0
        try {
            while (n < 24) {
                val r = socket.getInputStream().read(buf, n, 24 - n)
                if (r < 0) return ProbeResult.NotAdb
                n += r
            }
        } catch (e: SocketTimeoutException) {
            return ProbeResult.NotAdb // connected, then silence
        } catch (e: IOException) {
            return ProbeResult.NotAdb // reset
        }
        val b = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN)
        val cmd = b.getInt(0).toLong() and 0xFFFFFFFFL
        val magic = b.getInt(20).toLong() and 0xFFFFFFFFL
        if (magic != cmd xor 0xFFFFFFFFL) return ProbeResult.NotAdb
        return when (cmd) {
            AUTH -> ProbeResult.AdbAuthRequired
            CNXN -> ProbeResult.AdbOpen
            STLS -> ProbeResult.AdbTls
            else -> ProbeResult.NotAdb
        }
    }

    companion object {
        private const val AUTH = 0x48545541L
        private const val CNXN = 0x4e584e43L
        private const val STLS = 0x534c5453L

        @OptIn(ExperimentalCoroutinesApi::class)
        private val shared: CoroutineDispatcher by lazy { Dispatchers.IO.limitedParallelism(128) }

        /** The CNXN packet: version 0x01000000, max data 4096, payload "host::\0", additive checksum, valid magic. */
        fun connectPacket(): ByteArray {
            val payload = "host::".toByteArray(Charsets.US_ASCII) + 0
            val checksum = payload.sumOf { it.toInt() and 0xFF }
            return ByteBuffer.allocate(24 + payload.size).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(CNXN.toInt()).putInt(0x01000000).putInt(4096)
                .putInt(payload.size).putInt(checksum).putInt((CNXN xor 0xFFFFFFFFL).toInt())
                .put(payload).array()
        }
    }
}
