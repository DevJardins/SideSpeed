package io.github.devjardins.sidespeed.helper

import java.io.BufferedWriter
import java.io.IOException
import java.io.InputStream
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.Semaphore
import kotlin.concurrent.thread

/**
 * Connection handling for the shell-uid helper. Plain JVM (no android.*) so it can be unit-tested; [HelperMain]
 * supplies the Android-specific pieces.
 *
 * Order per connection, which is the security contract:
 *  1. A connection slot is taken; beyond [maxConnections] the socket is closed at once.
 *  2. [callerUid] identifies the peer. Unless it equals [allowedUid] the socket is closed and the refusal
 *     logged. No request byte has been read at this point.
 *  3. One request line is read (printable ASCII, at most [HelperProtocol.MAX_REQUEST_CHARS]) within
 *     [requestTimeoutMs]. After that there is no timeout, since a compile can take minutes.
 *  4. `ping` is answered by [pong]; for `ping <nonce>` the nonce is first written by [writeAck] (if that fails
 *     the reply is [HelperProtocol.EXIT_HELPER_ERROR] with no pong). Anything else must map through [CommandAllowlist.toArgv] and is then
 *     run by [runner] (a fixed argv, no shell). Everything else is refused with [HelperProtocol.EXIT_REFUSED].
 */
internal class HelperServer(
    private val server: ServerSocket,
    private val allowedUid: Int,
    private val callerUid: (Socket) -> Int?,
    private val pong: () -> String,
    private val runner: (argv: List<String>, onLine: (String) -> Unit) -> Int,
    private val writeAck: (nonce: String) -> Unit,
    private val log: (String) -> Unit,
    private val maxConnections: Int = MAX_CONNECTIONS,
    private val requestTimeoutMs: Int = REQUEST_TIMEOUT_MS,
) {
    private val slots = Semaphore(maxConnections)

    /** Accepts connections until [server] is closed. */
    fun serve() {
        while (!server.isClosed) {
            val socket = try {
                server.accept()
            } catch (e: IOException) {
                if (server.isClosed) break
                log("accept failed: ${e.message}")
                sleepQuietly(200)
                continue
            }
            if (!slots.tryAcquire()) {
                log("REFUSED connection from port ${socket.port}: $maxConnections connections already open")
                closeQuietly(socket)
                continue
            }
            try {
                thread(name = "helper-conn-${socket.port}", isDaemon = true) {
                    try {
                        handle(socket)
                    } catch (t: Throwable) {
                        log("connection error ${t::class.java.simpleName}: ${t.message}")
                    } finally {
                        closeQuietly(socket)
                        slots.release()
                    }
                }
            } catch (t: Throwable) {
                log("cannot start connection thread: ${t.message}")
                closeQuietly(socket)
                slots.release()
            }
        }
    }

    private fun handle(socket: Socket) {
        val uid = try {
            callerUid(socket)
        } catch (t: Throwable) {
            log("caller check failed: ${t::class.java.simpleName}: ${t.message}")
            null
        }
        if (uid == null || uid != allowedUid) {
            log("REFUSED connection from ${socket.inetAddress?.hostAddress}:${socket.port}: uid=${uid ?: "unknown"}")
            return
        }

        val out = Out(BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8)), log)
        socket.soTimeout = requestTimeoutMs
        val request = try {
            readRequestLine(socket.getInputStream())
        } catch (e: SocketTimeoutException) {
            log("uid=$uid: no request within $requestTimeoutMs ms")
            null
        }
        if (request == null) {
            log("uid=$uid: missing, malformed or overlong request")
            out.line("error: bad request")
            out.end(HelperProtocol.EXIT_REFUSED)
            return
        }
        socket.soTimeout = 0 // compiles can take minutes

        if (CommandAllowlist.isPing(request)) {
            val nonce = CommandAllowlist.pingNonce(request)
            if (nonce != null) {
                try {
                    writeAck(nonce)
                } catch (t: Throwable) {
                    log("uid=$uid: cannot write ping ack: ${t::class.java.simpleName}: ${t.message}")
                    out.line("error: helper could not write the ping ack: ${t.message}")
                    out.end(HelperProtocol.EXIT_HELPER_ERROR)
                    return
                }
            }
            out.line(pong())
            out.end(0)
            return
        }
        val argv = CommandAllowlist.toArgv(request)
        if (argv == null) {
            log("REFUSED request from uid=$uid: '$request'")
            out.line("error: request not allowed")
            out.end(HelperProtocol.EXIT_REFUSED)
            return
        }
        log("uid=$uid run $argv")
        val start = System.currentTimeMillis()
        val exit = try {
            runner(argv, out::line)
        } catch (t: Throwable) {
            log("uid=$uid run $argv failed: ${t::class.java.simpleName}: ${t.message}")
            out.line("error: helper could not run the command: ${t.message}")
            HelperProtocol.EXIT_HELPER_ERROR
        }
        out.end(exit)
        log("uid=$uid finished $argv exit=$exit in ${System.currentTimeMillis() - start} ms")
    }

    /** Writes response lines; once the client is gone further writes are dropped (the command still completes). */
    private class Out(private val w: BufferedWriter, private val log: (String) -> Unit) {
        @Volatile private var dead = false

        fun line(s: String) {
            if (dead) return
            try {
                w.write(s)
                w.write("\n")
                w.flush()
            } catch (e: IOException) {
                dead = true
                log("client went away: ${e.message}")
            }
        }

        fun end(exit: Int) = line("${HelperProtocol.END}$exit")
    }

    companion object {
        const val MAX_CONNECTIONS = 4
        const val REQUEST_TIMEOUT_MS = 10_000

        /**
         * Reads one request line byte by byte, so nothing past it is consumed. Returns null for an empty request,
         * a byte outside printable ASCII (0x20..0x7E), or more than [HelperProtocol.MAX_REQUEST_CHARS] chars.
         * The line ends at "\n" or at end of stream.
         */
        fun readRequestLine(input: InputStream): String? {
            val sb = StringBuilder()
            while (true) {
                val b = input.read()
                if (b == -1 || b == '\n'.code) return sb.toString().ifEmpty { null }
                if (b < 0x20 || b > 0x7E) return null
                if (sb.length >= HelperProtocol.MAX_REQUEST_CHARS) return null
                sb.append(b.toChar())
            }
        }

        /** True only for 127.0.0.1 (as an IPv4 address or ::ffff:127.0.0.1). */
        fun isLoopback127(addr: InetAddress?): Boolean {
            val b = addr?.address ?: return false
            return when (b.size) {
                4 -> b.contentEquals(LOOPBACK)
                16 -> (0 until 10).all { b[it] == 0.toByte() } &&
                    b[10] == 0xFF.toByte() && b[11] == 0xFF.toByte() &&
                    b.copyOfRange(12, 16).contentEquals(LOOPBACK)
                else -> false
            }
        }

        private val LOOPBACK = byteArrayOf(127, 0, 0, 1)

        private fun closeQuietly(s: Socket) = try { s.close() } catch (_: Throwable) {}

        private fun sleepQuietly(ms: Long) = try { Thread.sleep(ms) } catch (_: InterruptedException) {}
    }
}
