package io.github.devjardins.sidespeed.helper

import android.os.Process
import android.system.Os
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.BindException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.system.exitProcess

/**
 * SideSpeed's shell-uid helper. Started from an ADB shell with [HelperLaunch.command], so it runs as uid 2000
 * under `app_process` (no Application, no Context: plain JVM plus framework classes).
 *
 * It listens on 127.0.0.1 only (first free port of [HELPER_PORT_FIRST]..[HELPER_PORT_LAST], written to
 * [HelperProtocol.PORT_FILE]), accepts only connections whose socket is owned by SideSpeed's uid (checked in
 * /proc/net/tcp{,6} before any request byte is read), and runs only [CommandAllowlist] commands, as a fixed argv
 * via ProcessBuilder. It never runs `sh -c` and never writes settings. It exits when its APK is replaced.
 */
object HelperMain {
    private const val SIDESPEED_PACKAGE = "io.github.devjardins.sidespeed"
    private const val APK_CHECK_MS = 5_000L
    private const val LOG_MAX_BYTES = 256 * 1024L
    private const val BACKLOG = 16
    private const val PROC_RETRY_MS = 50L

    private val logLock = Any()

    @JvmStatic
    fun main(args: Array<String>) {
        val code = try {
            serve(args)
        } catch (t: Throwable) {
            log("FATAL ${t::class.java.name}: ${t.message}")
            1
        }
        exitProcess(code)
    }

    private fun serve(args: Array<String>): Int {
        val apk = args.firstOrNull { it.startsWith("--apk=") }?.removePrefix("--apk=")
            ?: System.getenv("CLASSPATH")?.split(':')?.firstOrNull()
        if (apk.isNullOrBlank()) {
            log("startup: no APK path (pass --apk=<path>); exiting")
            return 2
        }
        val apkFile = File(apk)
        if (!apkFile.isFile) {
            log("startup: APK $apk does not exist; exiting")
            return 2
        }
        val startSize = apkFile.length()
        val startMtime = apkFile.lastModified()
        log("startup: uid=${Process.myUid()} pid=${Process.myPid()} apk=$apk size=$startSize mtime=$startMtime")

        // Never fall back to allowing everyone: no SideSpeed uid, no helper.
        val allowedUid = lookUpSideSpeedUid()
        if (allowedUid == null) {
            log("startup: cannot determine SideSpeed's uid; exiting")
            return 1
        }
        log("startup: SideSpeed uid=$allowedUid is the only allowed caller")

        val server = bind() ?: run {
            log("startup: no free port in $HELPER_PORT_FIRST..$HELPER_PORT_LAST; exiting")
            return 1
        }
        val port = server.localPort
        log("startup: listening on 127.0.0.1:$port")
        writePortFile(port)

        thread(name = "apk-watch", isDaemon = true) {
            while (true) {
                try {
                    Thread.sleep(APK_CHECK_MS)
                    val f = File(apk)
                    val reason = when {
                        !f.exists() -> "APK disappeared"
                        f.length() != startSize -> "APK size changed (${f.length()} != $startSize)"
                        f.lastModified() != startMtime -> "APK mtime changed (${f.lastModified()} != $startMtime)"
                        else -> null
                    }
                    if (reason != null) {
                        log("self-exit: $reason")
                        try { server.close() } catch (_: Throwable) {}
                        deletePortFileIfOurs(port)
                        exitProcess(0)
                    }
                } catch (t: Throwable) {
                    log("apk-watch error ${t::class.java.simpleName}: ${t.message}")
                }
            }
        }

        HelperServer(
            server = server,
            allowedUid = allowedUid,
            callerUid = { socket -> callerUid(socket, port) },
            pong = { HelperProtocol.pong(Process.myUid(), apk) },
            runner = ::runArgv,
            writeAck = ::writeAckFile,
            log = ::log,
        ).serve()
        deletePortFileIfOurs(port)
        return 0
    }

    /** Binds 127.0.0.1 (never 0.0.0.0) on the first free port of the range. */
    private fun bind(): ServerSocket? {
        val loopback = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
        for (port in HELPER_PORT_FIRST..HELPER_PORT_LAST) {
            try {
                return ServerSocket(port, BACKLOG, loopback)
            } catch (e: BindException) {
                log("startup: port $port busy (${e.message})")
            } catch (e: IOException) {
                log("startup: port $port failed (${e.message})")
            }
        }
        return null
    }

    /**
     * The uid owning the client's socket. The accepted socket's remote port is the client's local port, so look
     * for the row whose local endpoint is 127.0.0.1:<that port> and whose remote endpoint is 127.0.0.1:<our port>.
     * One retry after [PROC_RETRY_MS] for the race where the row isn't visible yet.
     */
    private fun callerUid(socket: Socket, helperPort: Int): Int? {
        if (!HelperServer.isLoopback127(socket.inetAddress)) return null
        repeat(2) { attempt ->
            val uid = ProcNetTcp.ownerUid(readProc("/proc/net/tcp"), readProc("/proc/net/tcp6"), socket.port, helperPort)
            if (uid != null) return uid
            if (attempt == 0) Thread.sleep(PROC_RETRY_MS)
        }
        return null
    }

    private fun readProc(path: String): String = try {
        File(path).inputStream().bufferedReader().use { it.readText() }
    } catch (_: IOException) {
        "" // tcp6 can be absent on kernels without IPv6
    }

    /**
     * Runs a fixed argv (never through a shell), streaming merged stdout/stderr. Returns the exit code.
     * If reading the output fails, the child is destroyed and reaped so it can't linger as a zombie.
     */
    private fun runArgv(argv: List<String>, onLine: (String) -> Unit): Int {
        val p = ProcessBuilder(argv).redirectErrorStream(true).start()
        var done = false
        try {
            p.outputStream.close()
            p.inputStream.bufferedReader().useLines { lines -> lines.forEach(onLine) }
            val code = p.waitFor()
            done = true
            return code
        } finally {
            if (!done) {
                p.destroy()
                try {
                    p.waitFor()
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
        }
    }

    private fun lookUpSideSpeedUid(): Int? {
        try {
            val lines = capture(listOf("/system/bin/pm", "list", "packages", "-U", SIDESPEED_PACKAGE))
            SideSpeedUid.fromPmList(lines, SIDESPEED_PACKAGE)?.let { return it }
            log("uid lookup: pm list packages -U gave no match: ${lines.take(5)}")
        } catch (t: Throwable) {
            log("uid lookup: pm failed: ${t.message}")
        }
        try {
            val lines = capture(listOf("/system/bin/dumpsys", "package", SIDESPEED_PACKAGE))
            SideSpeedUid.fromDumpsys(lines, SIDESPEED_PACKAGE)?.let { return it }
            log("uid lookup: dumpsys package gave no userId for $SIDESPEED_PACKAGE")
        } catch (t: Throwable) {
            log("uid lookup: dumpsys failed: ${t.message}")
        }
        return null
    }

    private fun capture(argv: List<String>): List<String> {
        val lines = mutableListOf<String>()
        runArgv(argv) { lines += it }
        return lines
    }

    /** Writes the port atomically and makes it world-readable (0644) so the app can read it. */
    private fun writePortFile(port: Int) {
        val target = File(HelperProtocol.PORT_FILE)
        val tmp = File(HelperProtocol.PORT_FILE + ".tmp")
        try {
            tmp.delete()
            tmp.writeText("$port\n")
            Os.chmod(tmp.path, "644".toInt(8))
            if (!tmp.renameTo(target)) throw IOException("rename to ${target.path} failed")
            log("startup: wrote port $port to ${target.path}")
        } catch (t: Throwable) {
            log("startup: cannot write port file (${t::class.java.simpleName}: ${t.message}); the app cannot find the helper")
        }
    }

    private val ackLock = Any()

    /**
     * Writes a ping nonce to [HelperProtocol.ACK_FILE]: temp file, chmod 0644, rename, so a reader never sees a
     * partial nonce. The nonce was validated by [CommandAllowlist.pingNonce] (alphanumeric only). Throws on
     * failure so the ping is answered with an error instead of an unprovable pong.
     */
    private fun writeAckFile(nonce: String) = synchronized(ackLock) {
        val target = File(HelperProtocol.ACK_FILE)
        val tmp = File(HelperProtocol.ACK_FILE + ".tmp")
        tmp.delete()
        tmp.writeText("$nonce\n")
        Os.chmod(tmp.path, "644".toInt(8))
        if (!tmp.renameTo(target)) throw IOException("rename to ${target.path} failed")
    }

    private fun deletePortFileIfOurs(port: Int) {
        try {
            val f = File(HelperProtocol.PORT_FILE)
            if (f.readText().trim() == port.toString()) f.delete()
        } catch (_: Throwable) {
        }
    }

    private fun log(msg: String) {
        val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
        val line = "$ts [pid ${Process.myPid()}] $msg"
        synchronized(logLock) {
            println(line)
            try {
                val f = File(HelperProtocol.LOG_FILE)
                if (f.length() >= LOG_MAX_BYTES) f.renameTo(File(HelperProtocol.LOG_FILE + ".1"))
                FileOutputStream(f, true).use { it.write((line + "\n").toByteArray()) }
            } catch (_: Throwable) {
                // Log file unwritable: stdout only (which the launch command discards).
            }
        }
    }
}
