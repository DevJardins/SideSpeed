package io.github.devjardins.sidespeed.helper

import android.util.Log
import io.github.devjardins.sidespeed.adb.AdbConnectionLost
import io.github.devjardins.sidespeed.adb.CommandRunner
import io.github.devjardins.sidespeed.adb.ShellEvent
import io.github.devjardins.sidespeed.adb.shellFlow
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.job
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.SecureRandom

/** The outcome of streaming an update to the helper: every byte written, a failed install, or no helper. */
sealed interface InstallOutcome {
    object Started : InstallOutcome
    data class Failed(val exit: Int, val lastLine: String?) : InstallOutcome
    object HelperDown : InstallOutcome
}

/** A verified helper's answer to `ping`. */
data class HelperPing(val version: Int, val apkPath: String) {
    /** False when the helper runs code from another APK than [expectedApkPath] (ours was updated): restart it. */
    fun isCurrent(expectedApkPath: String): Boolean = apkPath == expectedApkPath

    companion object {
        // The path is everything after "apk=", so it may contain '=', '~~' or spaces.
        private val PONG = Regex("^pong v=(\\d{1,9}) uid=(\\d{1,9}) apk=(.+)$")

        /** Parses `pong v=<n> uid=<n> apk=<path>`, or null. */
        fun parse(line: String): HelperPing? {
            val m = PONG.matchEntire(line) ?: return null
            val version = m.groupValues[1].toIntOrNull() ?: return null
            return HelperPing(version, m.groupValues[3])
        }
    }
}

/**
 * Client for the shell-uid helper ([HelperMain]) on loopback TCP. See [HelperProtocol] for the wire format.
 *
 * Identity: the port file can be stale (helper killed, reboot), letting another app bind that port and fake
 * replies. [ping] therefore sends a fresh random nonce and only trusts the reply if the helper wrote that nonce
 * to [ackFile], which apps cannot write. [shell] only talks to the port verified by the last successful [ping].
 * Residual risk: if the helper dies between [ping] and [shell], another app could take the port in between.
 *
 * Retries (after each of [retryDelaysMs]) only cover immediate refusals: a refused connect, or a connection
 * closed or reset with no reply within [immediateCloseWindowMs] of connecting. That is how the helper turns
 * away connections over its cap (another app could briefly fill them) and failed caller checks, before reading
 * the request. A later close is never retried: `pm compile` prints nothing until it finishes, so a close minutes
 * in means the helper accepted the request and died, and resending it could re-run the compile (and would
 * widen the stale-port window). Reads run on [Dispatchers.IO]; cancelling the collector closes the socket.
 */
class HelperClient(
    private val portFile: File = File(HelperProtocol.PORT_FILE),
    private val connect: (Int) -> Socket = { Socket("127.0.0.1", it) },
    private val ackFile: File = File(HelperProtocol.ACK_FILE),
    private val retryDelaysMs: List<Long> = DEFAULT_RETRY_DELAYS_MS,
    private val immediateCloseWindowMs: Long = DEFAULT_IMMEDIATE_CLOSE_WINDOW_MS,
    private val log: (String) -> Unit = { Log.w(TAG, it) },
) : CommandRunner {
    private val random = SecureRandom()
    private val pingLock = Mutex()

    @Volatile private var verifiedPort: Int? = null

    /**
     * Pings the helper with a fresh nonce and checks [ackFile]. Returns null if the helper is not running,
     * refuses, cannot prove its identity, or speaks another protocol version. A non-null result also
     * verifies the port for [shell]; a null result clears it.
     */
    suspend fun ping(): HelperPing? = withContext(Dispatchers.IO) {
        pingLock.withLock {
            val (port, pong) = verify() ?: (null to null)
            verifiedPort = port
            pong
        }
    }

    /** One verification round: the port and its pong, or null. Never touches [verifiedPort] itself. */
    private suspend fun verify(): Pair<Int, HelperPing>? {
        val port = readPort() ?: return null
        val nonce = newNonce()
        val lines = mutableListOf<String>()
        val exit = try {
            exchange(port, "${CommandAllowlist.PING} $nonce", PING_READ_TIMEOUT_MS, clearOnFailure = false) {
                lines += it
            }
        } catch (e: AdbConnectionLost) {
            log("ping on port $port failed: ${e.message}")
            return null
        }
        if (exit != 0) {
            log("ping on port $port refused: exit=$exit ${lines.take(3)}")
            return null
        }
        val pong = lines.firstNotNullOfOrNull { HelperPing.parse(it) } ?: run {
            log("ping on port $port: no pong in ${lines.take(3)}")
            return null
        }
        val ack = try {
            ackFile.readText().trim()
        } catch (_: IOException) {
            null
        }
        if (ack != nonce) {
            log("ping on port $port: ack file does not hold our nonce; not trusting this server (stale port?)")
            return null
        }
        if (pong.version != HELPER_PROTOCOL_VERSION) {
            log("helper protocol v=${pong.version}, expected v=$HELPER_PROTOCOL_VERSION")
            return null
        }
        return port to pong
    }

    /** Runs [command] on the verified helper: Lines, then Exit. Throws [AdbConnectionLost] if unverified or cut off. */
    override fun shell(command: String): Flow<ShellEvent> = shellFlow {
        val port = verifiedPort ?: throw AdbConnectionLost("helper not verified")
        val exit = exchange(port, command, readTimeoutMs = 0) { emit(ShellEvent.Line(it)) }
        emit(ShellEvent.Exit(exit))
    }

    /**
     * Streams [apk] to the verified helper as `install-self <size>` + raw bytes. Returns [Started]
     * once every byte is written: the app expects to be killed by its own update shortly afterwards,
     * so END is not required for success (a broken connection after a full write still counts). An
     * `END<n>` with n≠0 that does arrive is [Failed] with the last output line. [HelperDown] when the
     * helper was never verified or the connect itself fails.
     */
    @OptIn(InternalCoroutinesApi::class)
    suspend fun installSelf(apk: File): InstallOutcome = withContext(Dispatchers.IO) {
        val port = verifiedPort ?: return@withContext InstallOutcome.HelperDown
        val size = apk.length()
        if (size <= 0) {
            return@withContext InstallOutcome.Failed(HelperProtocol.EXIT_HELPER_ERROR, "empty APK file")
        }
        val socket = try {
            runInterruptible { connect(port) }
        } catch (_: IOException) {
            if (verifiedPort == port) verifiedPort = null
            return@withContext InstallOutcome.HelperDown
        }
        val onCancel = currentCoroutineContext().job
            .invokeOnCompletion(onCancelling = true, invokeImmediately = true) { closeQuietly(socket) }
        try {
            socket.soTimeout = 0 // the install can take ~30 s, so no read timeout after the body
            val out = socket.getOutputStream()
            out.write("install-self $size\n".toByteArray(Charsets.US_ASCII))
            apk.inputStream().use { it.copyTo(out) }
            out.flush()
            val reader = socket.getInputStream().bufferedReader(Charsets.UTF_8)
            var last: String? = null
            while (true) {
                val line = try {
                    reader.readLine()
                } catch (e: IOException) {
                    currentCoroutineContext().ensureActive()
                    break
                } ?: break
                val end = HelperProtocol.parseEnd(line)
                if (end != null) {
                    return@withContext if (end == 0) InstallOutcome.Started
                    else InstallOutcome.Failed(end, last)
                }
                last = line
            }
            InstallOutcome.Started
        } finally {
            onCancel.dispose()
            closeQuietly(socket)
        }
    }

    /** Port from [portFile], if it holds a port of the helper's range. */
    private fun readPort(): Int? {
        val text = try {
            portFile.readText().trim()
        } catch (e: IOException) {
            log("no helper port file at ${portFile.path}: ${e.message}")
            return null
        }
        return text.toIntOrNull()?.takeIf { it in HELPER_PORT_FIRST..HELPER_PORT_LAST }
            ?: run { log("bad helper port file content '${text.take(16)}'"); null }
    }

    private fun newNonce(): String = String(CharArray(NONCE_LENGTH) { NONCE_CHARS[random.nextInt(NONCE_CHARS.length)] })

    /**
     * Sends one request and passes each response line to [onLine] until `__END__:<exit>`; returns the exit.
     * Throws [AdbConnectionLost] if the connection fails or ends before the end marker. Must run on an IO thread.
     */
    private suspend fun exchange(
        port: Int,
        request: String,
        readTimeoutMs: Int,
        clearOnFailure: Boolean = true,
        onLine: suspend (String) -> Unit,
    ): Int {
        val c = try {
            open(port, request, readTimeoutMs)
        } catch (e: AdbConnectionLost) {
            if (clearOnFailure && verifiedPort == port) verifiedPort = null
            throw e
        }
        try {
            var line: String? = c.first
            while (line != null) {
                HelperProtocol.parseEnd(line)?.let { return it }
                onLine(line)
                line = try {
                    c.reader.readLine()
                } catch (e: IOException) {
                    currentCoroutineContext().ensureActive()
                    throw AdbConnectionLost("helper connection lost", e)
                }
            }
            currentCoroutineContext().ensureActive()
            throw AdbConnectionLost("helper closed the connection before ${HelperProtocol.END}")
        } finally {
            c.close()
        }
    }

    private class Connection(
        val socket: Socket,
        val reader: BufferedReader,
        val first: String,
        private val onCancel: DisposableHandle,
    ) {
        fun close() {
            onCancel.dispose()
            closeQuietly(socket)
        }
    }

    /** An immediate refusal, safe to retry: the helper never acted on the request. */
    private class Refused(message: String, cause: Throwable? = null) : IOException(message, cause)

    /** Connects and reads the first response line, retrying only immediate refusals (see the class doc). */
    private suspend fun open(port: Int, request: String, readTimeoutMs: Int): Connection {
        var attempt = 0
        while (true) {
            val failure: IOException = try {
                return openOnce(port, request, readTimeoutMs)
            } catch (e: IOException) {
                e
            }
            currentCoroutineContext().ensureActive()
            if (failure !is Refused || attempt >= retryDelaysMs.size) {
                throw AdbConnectionLost("helper on port $port: ${failure.message}", failure)
            }
            log("helper port $port: ${failure.message}; retrying")
            delay(retryDelaysMs[attempt++])
        }
    }

    /**
     * One attempt. Throws [Refused] for a refused connect or a close/reset with no reply within
     * [immediateCloseWindowMs]; any other failure is a plain IOException (not retried).
     *
     * Cancelling the caller closes the socket, which is the only way to unblock a thread stuck in a socket read.
     * The handler must run when the job starts *cancelling*: a plain invokeOnCompletion only fires once the job
     * has completed, which it can't while its thread is blocked in the read, so collectors like first() would
     * wait forever.
     */
    @OptIn(InternalCoroutinesApi::class)
    private suspend fun openOnce(port: Int, request: String, readTimeoutMs: Int): Connection {
        val socket = try {
            runInterruptible { connect(port) }
        } catch (e: IOException) {
            throw Refused("connect failed: ${e.message}", e)
        }
        val connectedAt = System.nanoTime()
        fun elapsedMs() = (System.nanoTime() - connectedAt) / 1_000_000
        val onCancel = currentCoroutineContext().job
            .invokeOnCompletion(onCancelling = true, invokeImmediately = true) { closeQuietly(socket) }
        val first = try {
            socket.soTimeout = readTimeoutMs
            val out = socket.getOutputStream()
            out.write((request + "\n").toByteArray(Charsets.US_ASCII))
            out.flush()
            val reader = socket.getInputStream().bufferedReader(Charsets.UTF_8)
            reader.readLine()?.let { return Connection(socket, reader, it, onCancel) }
            null
        } catch (e: Throwable) {
            onCancel.dispose()
            closeQuietly(socket)
            val ms = elapsedMs()
            if (e is IOException && e !is SocketTimeoutException && ms < immediateCloseWindowMs) {
                throw Refused("connection reset after $ms ms with no reply: ${e.message}", e)
            }
            throw e
        }
        onCancel.dispose()
        closeQuietly(socket)
        val ms = elapsedMs()
        if (first == null && ms < immediateCloseWindowMs) throw Refused("closed after $ms ms with no reply")
        throw IOException("helper closed the connection after $ms ms without replying")
    }

    companion object {
        val DEFAULT_RETRY_DELAYS_MS: List<Long> = listOf(100L, 300L)

        /** A close this soon after connecting is the helper refusing the connection, not a failed command. */
        const val DEFAULT_IMMEDIATE_CLOSE_WINDOW_MS = 500L
        private const val TAG = "SideSpeedHelper"
        private const val PING_READ_TIMEOUT_MS = 5_000
        private const val NONCE_LENGTH = 32
        private const val NONCE_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"

        private fun closeQuietly(s: Socket) = try { s.close() } catch (_: Throwable) {}
    }
}
