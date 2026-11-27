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
 *  3. One request line `<nonce> <request>` is read (printable ASCII, at most
 *     [HelperProtocol.MAX_REQUEST_LINE_CHARS]) within [requestTimeoutMs]. After that there is no timeout,
 *     since a compile can take minutes. A missing or bad nonce is refused with
 *     [HelperProtocol.EXIT_REFUSED] and logged; nothing runs.
 *  4. The nonce is written by [writeAck] and a [HelperProtocol.PROVEN] line is sent first (if the write
 *     fails the reply is an error with [HelperProtocol.EXIT_HELPER_ERROR] and nothing runs). Only then
 *     is the request handled: `ping` is answered by [pong], `install-self <size>` and
 *     `install-update <pkg> <size>` stream the body into [installRunner] (the latter only for a
 *     package [packageCheck] confirms, and never with [bringBack]), anything else must map through
 *     [CommandAllowlist.toArgv] and is then run by [runner] (a fixed argv, no shell). Everything
 *     else is refused with [HelperProtocol.EXIT_REFUSED],
 *     after the proof: the client fails closed on any reply whose first line is not [HelperProtocol.PROVEN].
 */
internal class HelperServer(
    private val server: ServerSocket,
    private val allowedUid: Int,
    private val selfPkg: String,
    private val callerUid: (Socket) -> Int?,
    private val pong: () -> String,
    private val runner: (argv: List<String>, onLine: (String) -> Unit) -> Int,
    private val writeAck: (nonce: String) -> Unit,
    private val log: (String) -> Unit,
    private val maxConnections: Int = MAX_CONNECTIONS,
    private val requestTimeoutMs: Int = REQUEST_TIMEOUT_MS,
    private val installRunner: (
        argv: List<String>,
        stdin: InputStream,
        size: Long,
        onLine: (String) -> Unit,
    ) -> Int = { _, _, _, _ -> HelperProtocol.EXIT_HELPER_ERROR },
    private val bringBack: () -> Int = { 0 },
    private val installGate: InstallGate = InstallGate(),
    private val bodyTimeoutMs: Int = BODY_TIMEOUT_MS,
    /**
     * True when [pkg] is an installed third-party sideloaded app (not a
     * store app): the [HelperMain] check. Runs before any body byte of an
     * `install-update` is read; false refuses the request.
     */
    private val packageCheck: (String) -> Boolean = { false },
    /**
     * Captures `/system/bin/dumpsys adb` inside the helper for `adb-trusted-networks`.
     * Raw output never leaves the helper; only [TrustedBssids.parse] MAC lines are emitted.
     */
    private val adbDump: () -> Pair<Int, ByteArray> = { HelperProtocol.EXIT_HELPER_ERROR to ByteArray(0) },
) {
    init {
        require(selfPkg in SideSpeedPackages.ALL) { "unknown SideSpeed package: $selfPkg" }
    }

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
        val line = try {
            readRequestLine(socket.getInputStream())
        } catch (e: SocketTimeoutException) {
            log("uid=$uid: no request within $requestTimeoutMs ms")
            null
        }
        if (line == null) {
            log("uid=$uid: missing, malformed or overlong request")
            out.line("error: bad request")
            out.end(HelperProtocol.EXIT_REFUSED)
            return
        }
        val (nonce, request) = HelperProtocol.splitNonce(line) ?: run {
            log("REFUSED request with missing or bad nonce from uid=$uid")
            out.line("error: request not allowed")
            out.end(HelperProtocol.EXIT_REFUSED)
            return
        }
        socket.soTimeout = 0 // compiles can take minutes

        try {
            writeAck(nonce)
        } catch (t: Throwable) {
            log("uid=$uid: cannot write proof for the request nonce: ${t::class.java.simpleName}: ${t.message}")
            out.line("error: helper could not write the request proof: ${t.message}")
            out.end(HelperProtocol.EXIT_HELPER_ERROR)
            return
        }
        out.line(HelperProtocol.PROVEN)

        if (CommandAllowlist.isPing(request)) {
            out.line(pong())
            out.end(0)
            return
        }
        val installSize = CommandAllowlist.installSelfSize(request)
        if (installSize != null) {
            handleInstallSelf(socket, uid, out, installSize)
            return
        }
        val update = CommandAllowlist.installUpdate(request)
        if (update != null) {
            handleInstallUpdate(socket, uid, out, update.first, update.second)
            return
        }
        if (CommandAllowlist.isGrantRebootRecovery(request)) {
            handleGrantRebootRecovery(uid, out)
            return
        }
        if (CommandAllowlist.isRevokeRebootRecovery(request)) {
            handleRevokeRebootRecovery(uid, out)
            return
        }
        if (CommandAllowlist.isAdbTrustedNetworks(request)) {
            handleAdbTrustedNetworks(uid, out)
            return
        }
        val argv = CommandAllowlist.toArgv(request, selfPkg)
        if (argv == null) {
            log("REFUSED request from uid=$uid: '${HelperLog.sanitize(request)}'")
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

    /**
     * Runs `install-self <size>`: streams exactly [size] bytes from the socket into [installRunner]
     * (never more), then runs [bringBack] only when the install exited 0 with every byte delivered.
     * The runner keeps going after the client disconnects (the app is killed by its own update), and a
     * short body is a failure: no bring-back, non-zero exit. Runs under [installGate] so apk-watch
     * cannot exit the helper mid-install.
     */
    private fun handleInstallSelf(socket: Socket, uid: Int, out: Out, size: Long) {
        log("uid=$uid run install-self size=$size")
        streamInstallBody(socket, uid, out, CommandAllowlist.installSelfArgv(size, selfPkg), size, "install-self", true)
    }

    /**
     * Runs `install-update <pkg> <size>`: like `install-self`, except [packageCheck] must first
     * confirm [pkg] is an installed third-party sideloaded app (checked before any body byte is
     * read, so a refused request leaves the body unread), and a success never runs [bringBack]
     * (only our own update brings the app back).
     */
    private fun handleInstallUpdate(socket: Socket, uid: Int, out: Out, pkg: String, size: Long) {
        if (!packageCheck(pkg)) {
            log("REFUSED install-update $pkg: not an installed third-party sideloaded app")
            out.line("error: request not allowed")
            out.end(HelperProtocol.EXIT_REFUSED)
            return
        }
        log("uid=$uid run install-update pkg=$pkg size=$size")
        streamInstallBody(
            socket, uid, out,
            CommandAllowlist.installUpdateArgv(pkg, size), size, "install-update pkg=$pkg", false,
        )
    }

    /**
     * Grants reboot-recovery permissions in order (`WRITE_SECURE_SETTINGS` last). On any failure,
     * rolls back all four permissions in reverse order (`WRITE_SECURE_SETTINGS` first).
     */
    private fun handleGrantRebootRecovery(uid: Int, out: Out) {
        log("uid=$uid run grant-reboot-recovery")
        val start = System.currentTimeMillis()
        var failureExit: Int? = null
        for (argv in CommandAllowlist.grantRebootRecoveryArgvs(selfPkg)) {
            val code = try {
                runner(argv, out::line)
            } catch (t: Throwable) {
                log("uid=$uid grant $argv failed: ${t::class.java.simpleName}: ${t.message}")
                out.line("error: helper could not run the command: ${t.message}")
                HelperProtocol.EXIT_HELPER_ERROR
            }
            if (code != 0) {
                failureExit = code
                break
            }
        }
        if (failureExit != null) {
            log("uid=$uid grant-reboot-recovery failed exit=$failureExit; rolling back")
            runAllRevokes(uid, out)
            out.end(failureExit)
            log("uid=$uid finished grant-reboot-recovery exit=$failureExit in ${System.currentTimeMillis() - start} ms")
            return
        }
        out.end(0)
        log("uid=$uid finished grant-reboot-recovery exit=0 in ${System.currentTimeMillis() - start} ms")
    }

    /**
     * Revokes all four reboot-recovery permissions (`WRITE_SECURE_SETTINGS` first), continuing past
     * individual non-zero exits and client disconnects (revoking runtime permissions may kill the app).
     */
    private fun handleRevokeRebootRecovery(uid: Int, out: Out) {
        log("uid=$uid run revoke-reboot-recovery")
        val start = System.currentTimeMillis()
        val exit = runAllRevokes(uid, out)
        out.end(exit)
        log("uid=$uid finished revoke-reboot-recovery exit=$exit in ${System.currentTimeMillis() - start} ms")
    }

    private fun runAllRevokes(uid: Int, out: Out): Int {
        var firstError = 0
        for (argv in CommandAllowlist.revokeRebootRecoveryArgvs(selfPkg)) {
            val code = try {
                runner(argv, out::line)
            } catch (t: Throwable) {
                log("uid=$uid revoke $argv failed: ${t::class.java.simpleName}: ${t.message}")
                HelperProtocol.EXIT_HELPER_ERROR
            }
            if (code != 0 && firstError == 0) firstError = code
        }
        return firstError
    }

    /**
     * Runs `/system/bin/dumpsys adb` inside the helper and emits only validated lowercase MACs.
     * On non-zero exit or error, emits zero dump bytes.
     */
    private fun handleAdbTrustedNetworks(uid: Int, out: Out) {
        log("uid=$uid run adb-trusted-networks")
        val start = System.currentTimeMillis()
        val (exit, bytes) = try {
            adbDump()
        } catch (t: Throwable) {
            log("uid=$uid adb-trusted-networks failed: ${t::class.java.simpleName}: ${t.message}")
            HelperProtocol.EXIT_HELPER_ERROR to ByteArray(0)
        }
        if (exit == 0) {
            for (mac in TrustedBssids.parse(bytes)) {
                out.line(mac)
            }
        }
        out.end(exit)
        log("uid=$uid finished adb-trusted-networks exit=$exit in ${System.currentTimeMillis() - start} ms")
    }

    /**
     * Streams exactly [size] bytes from the socket into [installRunner] (never more). The runner
     * keeps going after the client disconnects, and a short body is a failure: no bring-back,
     * non-zero exit. Runs under [installGate] so apk-watch cannot exit the helper mid-install.
     * [describe] names the run in the log; [bringBackAfter] runs [bringBack] on success.
     */
    private fun streamInstallBody(
        socket: Socket,
        uid: Int,
        out: Out,
        argv: List<String>,
        size: Long,
        describe: String,
        bringBackAfter: Boolean,
    ) {
        val start = System.currentTimeMillis()
        // Bound a stalled body: without this the socket keeps soTimeout=0 (set for compiles),
        // and a client that stops mid-body would hold InstallGate and a slot forever. A timeout
        // surfaces as SocketTimeoutException (an IOException), i.e. a short body: the install is
        // destroyed and bring-back is skipped.
        socket.soTimeout = bodyTimeoutMs
        installGate.begin()
        try {
            val stdin = CappedInputStream(socket.getInputStream(), size)
            val exit = try {
                installRunner(argv, stdin, size, out::line)
            } catch (t: Throwable) {
                log("uid=$uid $describe failed: ${t::class.java.simpleName}: ${t.message}")
                out.line("error: helper could not run the install: ${t.message}")
                HelperProtocol.EXIT_HELPER_ERROR
            }
            val delivered = stdin.total >= size
            val finalExit = if (exit == 0 && !delivered) HelperProtocol.EXIT_HELPER_ERROR else exit
            if (finalExit == 0) {
                if (bringBackAfter) {
                    try {
                        bringBack()
                    } catch (t: Throwable) {
                        log("uid=$uid bring-back failed: ${t::class.java.simpleName}: ${t.message}")
                    }
                }
            } else {
                log("uid=$uid $describe not installed: exit=$exit delivered=$delivered/$size")
            }
            out.end(finalExit)
            log("uid=$uid finished $describe size=$size exit=$finalExit in ${System.currentTimeMillis() - start} ms")
        } finally {
            installGate.end()
        }
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
        const val BODY_TIMEOUT_MS = 30_000

        /**
         * Reads one request line byte by byte, so nothing past it is consumed. Returns null for an empty request,
         * a byte outside printable ASCII (0x20..0x7E), or more than [maxChars] chars.
         * The line ends at "\n" or at end of stream.
         */
        fun readRequestLine(
            input: InputStream,
            maxChars: Int = HelperProtocol.MAX_REQUEST_LINE_CHARS,
        ): String? {
            val sb = StringBuilder()
            while (true) {
                val b = input.read()
                if (b == -1 || b == '\n'.code) return sb.toString().ifEmpty { null }
                if (b < 0x20 || b > 0x7E) return null
                if (sb.length >= maxChars) return null
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

/**
 * Tracks in-flight `install-self` runs. HelperMain's apk-watch calls [awaitIdle] before exiting,
 * so an APK change during an install waits for bring-back instead of killing it.
 */
internal class InstallGate {
    private var active = 0

    @Synchronized fun begin() {
        active++
    }

    @Synchronized fun end() {
        if (active > 0) active--
        (this as Object).notifyAll()
    }

    @Synchronized fun awaitIdle() {
        while (active > 0) (this as Object).wait()
    }

    /**
     * Bounded variant for apk-watch: true when the gate went idle within [timeoutMs], false when
     * the deadline passed with an install still in flight (the caller then exits anyway instead
     * of blocking the helper's self-exit forever).
     */
    @Synchronized fun awaitIdle(timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (active > 0) {
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0) return false
            (this as Object).wait(remaining)
        }
        return true
    }
}

/** Exit-poll interval for [waitInstallExit]; a prompt exit costs at most one extra poll. */
private const val INSTALL_EXIT_POLL_MS = 50L

/**
 * Waits up to [timeoutMs] for an install child to exit. Returns its exit code, or null when the
 * deadline (or an interrupt) beats it — in which case [destroy] has run and the child has been
 * reaped first, so a hung `cmd package install` can neither linger nor come back as a zombie.
 * Plain JVM (no android.*) so it is unit-testable: the exit poll uses only API-1 calls, and the
 * production [destroy] kills forcibly on API 26+ ([HelperMain.runInstall]).
 */
internal fun waitInstallExit(
    child: Process,
    timeoutMs: Long,
    destroy: (Process) -> Unit = { it.destroy() },
): Int? {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (true) {
        try {
            return child.exitValue()
        } catch (_: IllegalThreadStateException) {
            // Still running.
        }
        if (System.currentTimeMillis() >= deadline) break
        try {
            Thread.sleep(INSTALL_EXIT_POLL_MS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            break
        }
    }
    destroy(child)
    try {
        child.waitFor()
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
    }
    return null
}

/** An [InputStream] view yielding at most [cap] bytes, counting what was read in [total]. */
internal class CappedInputStream(private val inner: InputStream, private val cap: Long) : InputStream() {
    var total = 0L
        private set

    override fun read(): Int {
        if (total >= cap) return -1
        val b = inner.read()
        if (b >= 0) total++
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (total >= cap) return -1
        val n = inner.read(b, off, minOf(len.toLong(), cap - total).toInt())
        if (n > 0) total += n
        return n
    }
}
