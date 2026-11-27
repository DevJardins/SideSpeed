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
    data class NeedsHelperStart(val reason: UiText, val cause: StartCause = StartCause.OTHER) : Readiness
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
    private val buildFingerprint: String,
) {
    /** [onLaunch] is called once ADB is connected, just before the helper launch command is sent. */
    suspend fun ensureReady(onLaunch: () -> Unit = {}): Readiness {
        val ping = helper.ping()
        if (ping != null) {
            if (!ping.isCurrent(expectedApk)) return Readiness.HelperNeedsRestart
            prefs.setOnBoxAdbRefusedBuild(null)
            return Readiness.HelperReady
        }
        if (refusedOnThisBuild()) return refusedNow()
        return connectAndLaunch(killFirst = false, onLaunch)
    }

    /** Kills the stale helper over own-ADB, then relaunches it. Skipped while this Fire OS build refuses on-box ADB. */
    suspend fun restartHelper(onLaunch: () -> Unit = {}): Readiness {
        if (refusedOnThisBuild()) return refusedNow()
        return connectAndLaunch(killFirst = true, onLaunch)
    }

    /**
     * Fire OS only. Google TV refuses classic ADB until wireless debugging is on or paired, so a refusal
     * there is not final and is never cached.
     */
    private suspend fun refusedOnThisBuild() =
        isFireOs && prefs.onBoxAdbRefusedBuild.first() == buildFingerprint

    /** The cached refusal: no new attempt, straight to the PC instructions. */
    private fun refusedNow() =
        Readiness.NeedsHelperStart(UiText.Res(R.string.setup_reason_on_box_refused), StartCause.REFUSED)

    private suspend fun connectAndLaunch(killFirst: Boolean, onLaunch: () -> Unit): Readiness {
        val targets = buildList {
            add(AdbTarget.Classic())
            if (prefs.pairedTls.first()) add(AdbTarget.WirelessTls)
        }
        var connected = false
        var classicRefused = false
        for (t in targets) {
            val r = try {
                withTimeout(timeoutMs) { adb.connect(t) }
            } catch (e: TimeoutCancellationException) {
                // An Allow prompt is up (on Fire OS too). Never cached: a late Allow must still work.
                return Readiness.WaitingForAllowPrompt
            }
            if (r.isSuccess) { connected = true; break }
            if (t is AdbTarget.Classic && isRefusal(r.exceptionOrNull())) classicRefused = true
        }
        if (!connected) {
            if (!classicRefused || !isFireOs) {
                return Readiness.NeedsHelperStart(
                    UiText.Res(R.string.setup_reason_adb_unreachable),
                    if (classicRefused) StartCause.REFUSED else StartCause.OTHER,
                )
            }
            prefs.setOnBoxAdbRefusedBuild(buildFingerprint)
            return refusedNow()
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
            if (helper.ping()?.isCurrent(expectedApk) == true) {
                prefs.setOnBoxAdbRefusedBuild(null)
                return Readiness.HelperReady
            }
            if (waited >= POLL_TOTAL_MS) break
            delay(POLL_INTERVAL_MS)
            waited += POLL_INTERVAL_MS
        }
        return Readiness.NeedsHelperStart(UiText.Res(R.string.setup_reason_helper_not_started))
    }

    /**
     * A closed port fails fast with [ConnectException] (possibly wrapped by the ADB library).
     * Fire OS keeps the port open but closes the stream before the handshake: [AdbClosedException].
     */
    private fun isRefusal(e: Throwable?): Boolean =
        generateSequence(e) { it.cause.takeIf { c -> c !== it } }.take(8)
            .any { it is ConnectException || it is AdbClosedException }

    companion object {
        const val SHELL_TIMEOUT_MS = 10_000L
        const val KILL_SETTLE_MS = 500L
        const val POLL_INTERVAL_MS = 500L
        const val POLL_TOTAL_MS = 5_000L
    }
}
