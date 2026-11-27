package io.github.devjardins.sidespeed.adb

import io.github.devjardins.sidespeed.R
import io.github.devjardins.sidespeed.data.Prefs
import io.github.devjardins.sidespeed.helper.HelperLaunch
import io.github.devjardins.sidespeed.helper.HelperPing
import io.github.devjardins.sidespeed.ui.text.UiText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.net.ConnectException

/**
 * Why the helper could not be started on the box. [REFUSED]: the classic ADB port was closed (a quick
 * "connection refused"), as opposed to a pending "Allow debugging?" prompt, which shows up as
 * [Readiness.WaitingForAllowPrompt].
 */
enum class StartCause { REFUSED, OTHER }

sealed interface Readiness {
    data object HelperReady : Readiness
    data object HelperNeedsRestart : Readiness
    data class NeedsHelperStart(
        val reason: UiText,
        val cause: StartCause = StartCause.OTHER,
        val mdnsMiss: Boolean = false,
    ) : Readiness
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

fun pcStartCommands(kind: DeviceKind, wifiIp: String?, pkg: String, killFirst: Boolean): PcStartCommands {
    val launch = HelperLaunch.command(pkg)
    require(!launch.contains('\'')) { "launch command must not contain single quotes" }
    val ip = wifiIp ?: "<tv-ip>"
    val connect = when (kind) {
        DeviceKind.FIRE_OS -> listOf("adb connect $ip:5555")
        DeviceKind.GOOGLE_TV -> listOf("adb pair $ip:<pairing port>", "adb connect $ip:<port>")
    }
    val raw = buildList {
        if (killFirst) add(HelperLaunch.killCommand(pkg))
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
    private val pkg: String,
    private val timeoutMs: Long = 30_000,
    private val adbMutex: Mutex = Mutex(),
) {
    /** [onLaunch] is called once ADB is connected, just before the helper launch command is sent. */
    suspend fun ensureReady(onLaunch: () -> Unit = {}): Readiness = adbMutex.withLock {
        val ping = helper.ping()
        if (ping != null) {
            return@withLock if (ping.isCurrent(expectedApk)) Readiness.HelperReady else Readiness.HelperNeedsRestart
        }
        if (isFireOs) return@withLock Readiness.NeedsHelperStart(UiText.Res(R.string.setup_reason_fire_os))
        connectAndLaunch(killFirst = false, onLaunch)
    }

    /** Google TV only: kills the stale helper over own-ADB, then relaunches it. */
    suspend fun restartHelper(onLaunch: () -> Unit = {}): Readiness = adbMutex.withLock {
        if (isFireOs) return@withLock Readiness.NeedsHelperStart(UiText.Res(R.string.setup_reason_fire_os))
        connectAndLaunch(killFirst = true, onLaunch)
    }

    private suspend fun connectAndLaunch(killFirst: Boolean, onLaunch: () -> Unit): Readiness {
        val targets = buildList {
            add(AdbTarget.Classic())
            if (prefs.pairedTls.first()) add(AdbTarget.WirelessTls)
        }
        var connected = false
        var classicRefused = false
        var tlsMdnsMiss = false
        try {
            for (t in targets) {
                val r = try {
                    withTimeout(timeoutMs) { adb.connect(t) }
                } catch (e: TimeoutCancellationException) {
                    return Readiness.WaitingForAllowPrompt
                }
                if (r.isSuccess) { connected = true; break }
                val err = r.exceptionOrNull()
                if (t is AdbTarget.Classic && isRefusal(err)) classicRefused = true
                if (t is AdbTarget.WirelessTls && isMdnsMiss(err)) tlsMdnsMiss = true
            }
            if (!connected) {
                return Readiness.NeedsHelperStart(
                    UiText.Res(R.string.setup_reason_adb_unreachable),
                    if (classicRefused) StartCause.REFUSED else StartCause.OTHER,
                    mdnsMiss = tlsMdnsMiss,
                )
            }
            onLaunch()

            try {
                if (killFirst) {
                    withTimeout(SHELL_TIMEOUT_MS) { adb.shell(HelperLaunch.killCommand(pkg)).collect { } }
                    delay(KILL_SETTLE_MS)
                }
                withTimeout(SHELL_TIMEOUT_MS) { adb.shell(HelperLaunch.command(pkg)).collect { } }
            } catch (e: TimeoutCancellationException) {
                return Readiness.NeedsHelperStart(UiText.Res(R.string.setup_reason_helper_not_started))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return Readiness.NeedsHelperStart(UiText.Res(R.string.setup_reason_helper_not_started))
            }
            var waited = 0L
            while (true) {
                if (helper.ping()?.isCurrent(expectedApk) == true) return Readiness.HelperReady
                if (waited >= POLL_TOTAL_MS) break
                delay(POLL_INTERVAL_MS)
                waited += POLL_INTERVAL_MS
            }
            return Readiness.NeedsHelperStart(UiText.Res(R.string.setup_reason_helper_not_started))
        } finally {
            if (connected) adb.close()
        }
    }

    /** A closed port fails fast with [ConnectException] (possibly wrapped by the ADB library). */
    private fun isRefusal(e: Throwable?): Boolean =
        generateSequence(e) { it.cause.takeIf { c -> c !== it } }.take(8).any { it is ConnectException }

    private fun isMdnsMiss(e: Throwable?): Boolean =
        generateSequence(e) { it.cause.takeIf { c -> c !== it } }.take(8).any { it is AdbMdnsMissException }

    companion object {
        const val SHELL_TIMEOUT_MS = 10_000L
        const val KILL_SETTLE_MS = 500L
        const val POLL_INTERVAL_MS = 500L
        const val POLL_TOTAL_MS = 5_000L
    }
}
