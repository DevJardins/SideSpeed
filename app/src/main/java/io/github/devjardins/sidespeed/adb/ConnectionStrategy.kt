package io.github.devjardins.sidespeed.adb

import io.github.devjardins.sidespeed.data.Prefs
import io.github.devjardins.sidespeed.helper.HelperLaunch
import io.github.devjardins.sidespeed.helper.HelperPing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout

sealed interface Readiness {
    data object HelperReady : Readiness
    data object HelperNeedsRestart : Readiness
    data class NeedsHelperStart(val reason: String) : Readiness
    data object WaitingForAllowPrompt : Readiness
}

fun interface HelperProbe {
    suspend fun ping(): HelperPing?
}

/** Commands to run from a PC. The launch command has `$(...)` and `|`, so it is wrapped in single quotes. */
fun pcStartCommands(wifiIp: String?, launch: String, killFirst: Boolean): List<String> {
    require(!launch.contains('\'')) { "launch command must not contain single quotes" }
    return buildList {
        add("adb connect ${wifiIp ?: "<tv-ip>"}:5555")
        if (killFirst) add("adb shell '${ConnectionStrategy.KILL_COMMAND}'")
        add("adb shell '$launch'")
    }
}

class ConnectionStrategy(
    private val helper: HelperProbe,
    private val adb: AdbBackend,
    private val prefs: Prefs,
    private val expectedApk: String,
    private val isFireOs: Boolean,
    private val timeoutMs: Long = 30_000,
) {
    suspend fun ensureReady(): Readiness {
        val ping = helper.ping()
        if (ping != null) {
            return if (ping.isCurrent(expectedApk)) Readiness.HelperReady else Readiness.HelperNeedsRestart
        }
        if (isFireOs) return Readiness.NeedsHelperStart("Fire OS blocks on-box ADB")
        return connectAndLaunch(killFirst = false)
    }

    /** Google TV only: kills the stale helper over own-ADB, then relaunches it. */
    suspend fun restartHelper(): Readiness {
        if (isFireOs) return Readiness.NeedsHelperStart("Fire OS blocks on-box ADB")
        return connectAndLaunch(killFirst = true)
    }

    private suspend fun connectAndLaunch(killFirst: Boolean): Readiness {
        val targets = buildList {
            add(AdbTarget.Classic())
            if (prefs.pairedTls.first()) add(AdbTarget.WirelessTls)
        }
        var connected = false
        for (t in targets) {
            val r = try {
                withTimeout(timeoutMs) { adb.connect(t) }
            } catch (e: TimeoutCancellationException) {
                return Readiness.WaitingForAllowPrompt
            }
            if (r.isSuccess) { connected = true; break }
        }
        if (!connected) return Readiness.NeedsHelperStart("ADB not reachable")

        try {
            if (killFirst) adb.shell(KILL_COMMAND).collect { }
            adb.shell(HelperLaunch.command()).collect { }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return Readiness.NeedsHelperStart("Helper did not start")
        }
        var waited = 0L
        while (true) {
            if (helper.ping()?.isCurrent(expectedApk) == true) return Readiness.HelperReady
            if (waited >= POLL_TOTAL_MS) break
            delay(POLL_INTERVAL_MS)
            waited += POLL_INTERVAL_MS
        }
        return Readiness.NeedsHelperStart("Helper did not start")
    }

    companion object {
        const val KILL_COMMAND = "kill \$(pidof sidespeed_helper)"
        const val POLL_INTERVAL_MS = 500L
        const val POLL_TOTAL_MS = 5_000L
    }
}
