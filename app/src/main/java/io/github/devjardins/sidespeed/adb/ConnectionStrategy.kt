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

enum class DeviceKind {
    FIRE_OS, GOOGLE_TV;

    companion object {
        fun of(isFireOs: Boolean): DeviceKind = if (isFireOs) FIRE_OS else GOOGLE_TV
    }
}

/**
 * Commands for starting the helper from a PC.
 * [connect]: how to reach the TV (classic ADB on 5555 for Fire OS; Wireless-debugging pair + connect for
 * Google TV, whose port 5555 is closed). [run]: one `adb shell '<cmd>'` per command; the launch command has
 * `$(...)` and `|`, so it is single-quoted, which Windows Command Prompt does not honour. [shellPaste]: the
 * raw commands, for typing `adb shell` and pasting them, which works from any terminal.
 */
data class PcStartCommands(val connect: List<String>, val run: List<String>, val shellPaste: List<String>)

fun pcStartCommands(kind: DeviceKind, wifiIp: String?, launch: String, killFirst: Boolean): PcStartCommands {
    require(!launch.contains('\'')) { "launch command must not contain single quotes" }
    val ip = wifiIp ?: "<tv-ip>"
    val connect = when (kind) {
        DeviceKind.FIRE_OS -> listOf("adb connect $ip:5555")
        DeviceKind.GOOGLE_TV -> listOf("adb pair $ip:<pairing port>", "adb connect $ip:<port>")
    }
    val raw = buildList {
        if (killFirst) add(ConnectionStrategy.KILL_COMMAND)
        add(launch)
    }
    return PcStartCommands(connect, raw.map { "adb shell '$it'" }, raw)
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
            if (killFirst) {
                withTimeout(SHELL_TIMEOUT_MS) { adb.shell(KILL_COMMAND).collect { } }
                delay(KILL_SETTLE_MS)
            }
            withTimeout(SHELL_TIMEOUT_MS) { adb.shell(HelperLaunch.command()).collect { } }
        } catch (e: TimeoutCancellationException) {
            return Readiness.NeedsHelperStart("Helper did not start")
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
        const val SHELL_TIMEOUT_MS = 10_000L
        const val KILL_SETTLE_MS = 500L
        const val POLL_INTERVAL_MS = 500L
        const val POLL_TOTAL_MS = 5_000L
    }
}
