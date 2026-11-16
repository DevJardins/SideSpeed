package io.github.devjardins.sidespeed.companion.tv

import kotlinx.coroutines.delay

sealed interface VisitResult {
    /** Helper current, nothing done. */
    data object Running : VisitResult

    /** Was missing, now running. */
    data object Started : VisitResult

    /** Was stale, killed and relaunched. */
    data object Restarted : VisitResult
    data object NotInstalled : VisitResult

    /** Serial mismatch; nothing else was sent. */
    data object WrongDevice : VisitResult
    data class StartFailed(val detail: String) : VisitResult
}

/** Checks SideSpeed's helper on a TV and (re)starts it if it is missing or belongs to an older install. */
class HelperKeeper(
    private val pollIntervalMs: Long = 500,
    private val pollTimeoutMs: Long = 5_000,
) {
    init {
        require(pollIntervalMs > 0 && pollTimeoutMs >= 0) { "invalid poll settings" }
    }

    suspend fun visit(session: TvSession, expectedSerial: String): VisitResult {
        // A blank serial on either side means the device is not confirmed: send nothing else.
        val serial = session.run(TvCommands.SERIAL)
        if (serial.isEmpty() || expectedSerial.isEmpty() || serial != expectedSerial) return VisitResult.WrongDevice

        val apk = apkPath(session.run(TvCommands.PKG_PATH)) ?: return VisitResult.NotInstalled

        val pids = parsePids(session.run(TvCommands.PIDOF))
        val running = pids.isNotEmpty()
        // Read every pid's cmdline (no short-circuit): any stale helper means a restart.
        val stale = pids.map { pid -> helperApk(session.run(TvCommands.cmdline(pid))) != apk }.any { it }

        if (running && !stale) return VisitResult.Running

        if (stale) {
            session.run(TvCommands.KILL)
            delay(KILL_SETTLE_MS)
        }
        session.run(TvCommands.LAUNCH)

        var waited = 0L
        while (true) {
            if (parsePids(session.run(TvCommands.PIDOF)).isNotEmpty()) {
                return if (stale) VisitResult.Restarted else VisitResult.Started
            }
            if (waited >= pollTimeoutMs) break
            delay(pollIntervalMs)
            waited += pollIntervalMs
        }
        return VisitResult.StartFailed("Helper did not appear within ${pollTimeoutMs}ms")
    }

    private fun apkPath(pmOutput: String): String? {
        val paths = pmOutput.lines().map { it.trim() }.filter { it.startsWith("package:") }
            .map { it.removePrefix("package:") }.filter { it.isNotEmpty() }
        return paths.firstOrNull { it.endsWith("base.apk") } ?: paths.firstOrNull()
    }

    /** Anything that is not whitespace-separated digits is treated as "not running". */
    private fun parsePids(output: String): List<Int> {
        val tokens = output.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (tokens.isEmpty() || tokens.any { t -> t.any { it !in '0'..'9' } }) return emptyList()
        val pids = tokens.map { it.toIntOrNull() ?: return emptyList() }
        return if (pids.all { it > 0 }) pids else emptyList()
    }

    private fun helperApk(cmdline: String): String? =
        cmdline.split('\u0000').firstOrNull { it.startsWith("--apk=") }?.removePrefix("--apk=")

    private companion object {
        const val KILL_SETTLE_MS = 500L
    }
}
