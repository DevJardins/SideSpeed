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

    private fun run(exe: String): String? {
        // Output goes to a file, not a pipe: reading a pipe to its end would block past the timeout when the
        // CLI hangs with stdout open, and a file also keeps a chatty stderr from filling a pipe.
        val out = File.createTempFile("te-tailscale", ".json")
        return try {
            val pb = ProcessBuilder(exe, "status", "--json")
                .redirectInput(ProcessBuilder.Redirect.from(File(if (File.separatorChar == '\\') "NUL" else "/dev/null")))
                .redirectOutput(out)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
            if (exe == APP_BUNDLE_EXE && pb.environment()["SHLVL"].isNullOrEmpty()) {
                // The Mac App Store build only speaks CLI when SHLVL is set: a Finder-launched app has no
                // SHLVL, so the binary prints a GUI error on stdout with exit 0. A shell always sets SHLVL,
                // which is why the /usr/local/bin/tailscale sh wrapper works under env -i.
                pb.environment()["SHLVL"] = "1"
            }
            val p = pb.start()
            if (!p.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                p.destroyForcibly()
                null
            } else if (p.exitValue() == 0) {
                // The app-bundle binary prints a GUI error on stdout with exit 0 when it misfires; only
                // JSON can be a status object, so anything else moves on to the next candidate.
                out.readText().takeIf { it.trimStart().startsWith("{") }
            } else null
        } catch (_: Exception) {
            null
        } finally {
            out.delete()
        }
    }

    companion object {
        const val APP_BUNDLE_EXE = "/Applications/Tailscale.app/Contents/MacOS/Tailscale"

        fun defaultCandidates(): List<String> = listOf(
            "tailscale",
            "/usr/local/bin/tailscale",
            "/opt/homebrew/bin/tailscale",
            APP_BUNDLE_EXE,
            "/usr/bin/tailscale",
            "C:\\Program Files\\Tailscale\\tailscale.exe",
        )
    }
}
