package io.github.devjardins.sidespeed.spike

import android.net.LocalServerSocket
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.Process
import io.github.devjardins.sidespeed.compile.PackageName
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.system.exitProcess

/**
 * Throwaway M0b spike: a helper process that runs as the shell user (uid 2000).
 *
 * Started from an ADB shell with:
 *   CLASSPATH=<apk> app_process /system/bin --nice-name=sidespeed_helper \
 *       io.github.devjardins.sidespeed.spike.HelperSpikeMain --apk=<apk>
 *
 * Client mode (for testing from `adb shell`, uid 2000 is allowed in the spike):
 *   CLASSPATH=<apk> app_process /system/bin io.github.devjardins.sidespeed.spike.HelperSpikeMain --client "ping"
 *
 * Runs under app_process: no Application, no Context. Only plain JVM + framework classes.
 */
object HelperSpikeMain {
    const val SOCKET_NAME = "sidespeed_helper"
    const val END_PREFIX = "__END__:"
    const val SIDESPEED_PACKAGE = "io.github.devjardins.sidespeed"
    private const val SHELL_UID = 2000
    private const val LOG_PATH = "/data/local/tmp/sidespeed_helper.log"
    private const val TICK_MS = 5_000L
    private const val MAX_ADBOFF_SECONDS = 120
    private const val MAX_REQUEST_CHARS = 256

    private val logLock = Any()
    private val adbOffActive = AtomicBoolean(false)

    @Volatile private var apkPath: String = ""
    @Volatile private var sideSpeedUid: Int = -1

    @JvmStatic
    fun main(args: Array<String>) {
        val clientIdx = args.indexOf("--client")
        if (clientIdx >= 0) {
            val req = args.getOrNull(clientIdx + 1)
            if (req == null) {
                System.err.println("usage: --client \"<request>\"")
                exitProcess(2)
            }
            exitProcess(runClient(req))
        }
        try {
            runServer(args)
        } catch (t: Throwable) {
            log("FATAL ${t::class.java.name}: ${t.message}")
            restoreAdbIfNeeded("fatal")
            exitProcess(1)
        }
    }

    // ---------------- server ----------------

    private fun runServer(args: Array<String>) {
        val apk = args.firstOrNull { it.startsWith("--apk=") }?.removePrefix("--apk=")
            ?: System.getenv("CLASSPATH")?.split(':')?.firstOrNull()
        if (apk.isNullOrBlank()) {
            log("startup: no APK path (pass --apk=<path> or set CLASSPATH); exiting")
            exitProcess(2)
        }
        apkPath = apk
        val apkFile = File(apk)
        if (!apkFile.isFile) {
            log("startup: APK $apk does not exist; exiting")
            exitProcess(2)
        }
        val startSize = apkFile.length()
        val startMtime = apkFile.lastModified()

        log("startup: uid=${Process.myUid()} pid=${Process.myPid()} apk=$apk size=$startSize mtime=$startMtime")
        sideSpeedUid = lookUpSideSpeedUid()
        log("startup: SideSpeed uid=$sideSpeedUid (allowed peers: ${allowedUidsText()})")

        val server = try {
            LocalServerSocket(SOCKET_NAME)
        } catch (e: IOException) {
            // Most likely another helper already holds the name.
            log("startup: cannot bind @$SOCKET_NAME (${e.message}); another helper running? exiting")
            exitProcess(1)
        }
        log("startup: listening on @$SOCKET_NAME")

        thread(name = "heartbeat", isDaemon = true) {
            var n = 0L
            while (true) {
                try {
                    Thread.sleep(TICK_MS)
                    n++
                    log("heartbeat n=$n adboff=${adbOffActive.get()}")
                    val f = File(apk)
                    val reason = when {
                        !f.exists() -> "APK disappeared"
                        f.length() != startSize -> "APK size changed (${f.length()} != $startSize)"
                        f.lastModified() != startMtime -> "APK mtime changed (${f.lastModified()} != $startMtime)"
                        else -> null
                    }
                    if (reason != null) {
                        log("self-exit: $reason")
                        restoreAdbIfNeeded("self-exit")
                        try { server.close() } catch (_: Throwable) {}
                        exitProcess(0)
                    }
                } catch (t: Throwable) {
                    log("heartbeat error ${t::class.java.simpleName}: ${t.message}")
                }
            }
        }

        while (true) {
            val socket = try {
                server.accept()
            } catch (e: IOException) {
                log("accept failed: ${e.message}")
                Thread.sleep(200)
                continue
            }
            thread(name = "conn", isDaemon = true) { handle(socket) }
        }
    }

    private fun allowedUidsText() =
        if (sideSpeedUid >= 10000) "$sideSpeedUid, $SHELL_UID" else "$SHELL_UID only (SideSpeed uid lookup failed)"

    private fun isAllowed(uid: Int): Boolean =
        uid == SHELL_UID || (sideSpeedUid >= 10000 && uid == sideSpeedUid)

    private fun handle(socket: LocalSocket) {
        socket.use { s ->
            val peerUid = try {
                s.peerCredentials.uid
            } catch (t: Throwable) {
                log("REFUSED connection: cannot read peer credentials (${t.message})")
                return
            }
            if (!isAllowed(peerUid)) {
                log("REFUSED connection from uid=$peerUid")
                return
            }
            val out = Out(BufferedWriter(OutputStreamWriter(s.outputStream, Charsets.UTF_8)))
            try {
                s.soTimeout = 10_000
                val req = readRequestLine(BufferedReader(InputStreamReader(s.inputStream, Charsets.UTF_8)))
                if (req == null) {
                    log("uid=$peerUid: no request line")
                    out.end(2)
                    return
                }
                log("uid=$peerUid request: $req")
                val exit = dispatch(req, out)
                out.end(exit)
                log("uid=$peerUid request '$req' finished exit=$exit")
            } catch (t: Throwable) {
                log("uid=$peerUid handler error ${t::class.java.simpleName}: ${t.message}")
                out.end(1)
            }
        }
    }

    /** Reads one line, at most [MAX_REQUEST_CHARS] chars. Returns null on EOF or overlong input. */
    private fun readRequestLine(r: BufferedReader): String? {
        val sb = StringBuilder()
        while (true) {
            val c = r.read()
            if (c == -1) return sb.toString().trim().ifEmpty { null }
            if (c == '\n'.code) return sb.toString().trim()
            if (sb.length >= MAX_REQUEST_CHARS) return null
            sb.append(c.toChar())
        }
    }

    /** Fixed request set. Never passes input to a shell; package names are validated. */
    private fun dispatch(req: String, out: Out): Int {
        val parts = req.split(' ').filter { it.isNotEmpty() }
        return when (parts.firstOrNull()) {
            "ping" -> {
                if (parts.size != 1) return out.error("usage: ping")
                out.line("pong uid=${Process.myUid()} pid=${Process.myPid()} apk=$apkPath")
                0
            }
            "id" -> {
                if (parts.size != 1) return out.error("usage: id")
                run(listOf("/system/bin/id"), out)
            }
            "compile" -> {
                if (parts.size != 2) return out.error("usage: compile <package>")
                val pkg = PackageName.parse(parts[1]) ?: return out.error("invalid package name")
                run(listOf("/system/bin/pm", "compile", "-m", "speed", "-f", pkg.value), out)
            }
            "adboff" -> {
                if (parts.size != 2) return out.error("usage: adboff <seconds 1..$MAX_ADBOFF_SECONDS>")
                val secs = parts[1].takeIf { it.length <= 3 && it.all(Char::isDigit) }?.toInt()
                if (secs == null || secs !in 1..MAX_ADBOFF_SECONDS) {
                    return out.error("seconds must be 1..$MAX_ADBOFF_SECONDS")
                }
                adbOff(secs, out)
            }
            else -> out.error("unknown request")
        }
    }

    private fun adbOff(secs: Int, out: Out): Int {
        if (!adbOffActive.compareAndSet(false, true)) return out.error("adboff already in progress")
        try {
            log("adboff: writing adb_enabled=0 for $secs s")
            out.line("adboff: setting adb_enabled 0")
            val r0 = run(listOf("/system/bin/settings", "put", "global", "adb_enabled", "0"), out)
            log("adboff: settings put adb_enabled 0 -> exit=$r0")
            if (r0 != 0) return r0
            out.line("adboff: sleeping $secs s")
            Thread.sleep(secs * 1000L)
            log("adboff: sleep done")
        } finally {
            val r1 = setAdbEnabled1()
            out.line("adboff: setting adb_enabled 1 -> exit=$r1")
            adbOffActive.set(false)
        }
        return 0
    }

    private fun setAdbEnabled1(): Int {
        val r = runQuiet(listOf("/system/bin/settings", "put", "global", "adb_enabled", "1"))
        log("adboff: settings put adb_enabled 1 -> exit=$r")
        return r
    }

    private fun restoreAdbIfNeeded(why: String) {
        if (adbOffActive.get()) {
            log("$why: adboff in progress, restoring adb_enabled=1 first")
            setAdbEnabled1()
        }
    }

    /** Runs a fixed argv (no shell), streaming merged stdout/stderr to [out]. Returns the exit code. */
    private fun run(argv: List<String>, out: Out): Int {
        val p = ProcessBuilder(argv).redirectErrorStream(true).start()
        p.outputStream.close()
        p.inputStream.bufferedReader().useLines { lines -> lines.forEach { out.line(it) } }
        return p.waitFor()
    }

    private fun runQuiet(argv: List<String>): Int = try {
        val p = ProcessBuilder(argv).redirectErrorStream(true).start()
        p.outputStream.close()
        p.inputStream.bufferedReader().useLines { lines -> lines.forEach { log("  | $it") } }
        p.waitFor()
    } catch (t: Throwable) {
        log("run $argv failed: ${t.message}")
        -1
    }

    /**
     * SideSpeed's uid for user 0, from `pm list packages -U <pkg>` (line `package:<pkg> uid:<n>`),
     * falling back to `dumpsys package <pkg>` (`userId=<n>`). -1 if not found.
     */
    private fun lookUpSideSpeedUid(): Int {
        try {
            val lines = capture(listOf("/system/bin/pm", "list", "packages", "-U", SIDESPEED_PACKAGE))
            val re = Regex("^package:${Regex.escape(SIDESPEED_PACKAGE)}\\s+uid:(\\d+)")
            lines.firstNotNullOfOrNull { re.find(it.trim())?.groupValues?.get(1)?.toIntOrNull() }?.let { return it }
            log("uid lookup: pm list packages -U gave no match: ${lines.take(5)}")
        } catch (t: Throwable) {
            log("uid lookup: pm failed: ${t.message}")
        }
        try {
            val lines = capture(listOf("/system/bin/dumpsys", "package", SIDESPEED_PACKAGE))
            val re = Regex("\\buserId=(\\d+)")
            lines.firstNotNullOfOrNull { re.find(it)?.groupValues?.get(1)?.toIntOrNull() }?.let { return it }
            log("uid lookup: dumpsys gave no userId= line")
        } catch (t: Throwable) {
            log("uid lookup: dumpsys failed: ${t.message}")
        }
        return -1
    }

    private fun capture(argv: List<String>): List<String> {
        val p = ProcessBuilder(argv).redirectErrorStream(true).start()
        p.outputStream.close()
        val lines = p.inputStream.bufferedReader().readLines()
        p.waitFor()
        return lines
    }

    /** Writes to the client; once the client is gone, further writes are dropped (work still completes). */
    private class Out(private val w: BufferedWriter) {
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

        fun error(msg: String): Int {
            line("error: $msg")
            return 2
        }

        fun end(exit: Int) = line("$END_PREFIX$exit")
    }

    private fun log(msg: String) {
        val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
        val line = "$ts [pid ${Process.myPid()}] $msg"
        synchronized(logLock) {
            println(line)
            try {
                FileOutputStream(LOG_PATH, true).use { it.write((line + "\n").toByteArray()) }
            } catch (_: Throwable) {
                // Log file unwritable: stdout only.
            }
        }
    }

    // ---------------- client ----------------

    /**
     * Sends one request to the helper and calls [onLine] for each response line before `__END__`.
     * Returns the helper's exit code, or null if the stream ended without `__END__`.
     * Throws IOException if the socket cannot be reached. Shared by `--client` and SpikeActivity.
     */
    fun request(req: String, timeoutMs: Int = 180_000, onLine: (String) -> Unit): Int? {
        LocalSocket().use { s ->
            s.connect(LocalSocketAddress(SOCKET_NAME, LocalSocketAddress.Namespace.ABSTRACT))
            s.soTimeout = timeoutMs
            val w = OutputStreamWriter(s.outputStream, Charsets.UTF_8)
            w.write(req.replace("\n", " ") + "\n")
            w.flush()
            val r = BufferedReader(InputStreamReader(s.inputStream, Charsets.UTF_8))
            while (true) {
                val line = r.readLine() ?: return null
                if (line.startsWith(END_PREFIX)) return line.removePrefix(END_PREFIX).trim().toIntOrNull()
                onLine(line)
            }
        }
    }

    private fun runClient(req: String): Int = try {
        val exit = request(req) { println(it) }
        if (exit == null) {
            System.err.println("client: stream ended without $END_PREFIX")
            255
        } else {
            exit
        }
    } catch (t: Throwable) {
        System.err.println("client: ${t::class.java.simpleName}: ${t.message}")
        254
    }
}
