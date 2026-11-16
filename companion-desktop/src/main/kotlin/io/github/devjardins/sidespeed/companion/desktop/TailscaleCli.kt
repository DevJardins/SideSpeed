package io.github.devjardins.sidespeed.companion.desktop

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Runs `tailscale status --json` and returns its output, or null when Tailscale is not installed, not running,
 * or does not answer within [timeoutSeconds]. It only reads; it never changes Tailscale's state.
 */
class TailscaleCli(
    private val candidates: List<String> = defaultCandidates(),
    private val timeoutSeconds: Long = 5,
) {
    suspend fun statusJson(): String? = withContext(Dispatchers.IO) {
        // A bare name is looked up on PATH by the OS; a path must exist and be executable.
        candidates.asSequence()
            .filter { !it.contains(File.separatorChar) || File(it).canExecute() }
            .firstNotNullOfOrNull(::run)
    }

    private fun run(exe: String): String? = try {
        val p = ProcessBuilder(exe, "status", "--json").redirectErrorStream(false).start()
        p.outputStream.close()
        val out = p.inputStream.bufferedReader().use { it.readText() }
        if (!p.waitFor(timeoutSeconds, TimeUnit.SECONDS)) { p.destroyForcibly(); null }
        else if (p.exitValue() == 0) out.takeIf { it.isNotBlank() } else null
    } catch (_: Exception) {
        null
    }

    companion object {
        fun defaultCandidates(): List<String> = listOf(
            "tailscale",
            "/Applications/Tailscale.app/Contents/MacOS/Tailscale",
            "/opt/homebrew/bin/tailscale",
            "/usr/local/bin/tailscale",
            "/usr/bin/tailscale",
            "C:\\Program Files\\Tailscale\\tailscale.exe",
        )
    }
}
