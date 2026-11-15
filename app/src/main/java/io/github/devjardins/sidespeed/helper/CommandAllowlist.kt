package io.github.devjardins.sidespeed.helper

import io.github.devjardins.sidespeed.compile.PackageName

/**
 * The only commands the shell-uid helper will run. Matching is exact and strict, with no normalisation:
 * tokens are separated by exactly one ASCII space, with no leading or trailing whitespace, no tabs and no
 * control characters. Anything else is refused. The result is a fixed argv for [ProcessBuilder]; it is never
 * passed to a shell.
 */
object CommandAllowlist {
    const val PING = "ping"

    private val MODES = setOf("speed", "speed-profile")

    /** Characters a valid request can contain at all: letters, digits, '.', '_', '-' and single spaces. */
    private val ALLOWED_CHARS = Regex("^[A-Za-z0-9._ -]+$")

    /** A ping nonce: 16 to 64 ASCII letters and digits. */
    private val NONCE = Regex("^[A-Za-z0-9]{16,64}$")

    /**
     * True for `ping` and `ping <nonce>`, which the helper answers itself (nothing is executed).
     * For the nonce form see [pingNonce] and [HelperProtocol.ACK_FILE].
     */
    fun isPing(command: String): Boolean = command == PING || pingNonce(command) != null

    /** The nonce of a `ping <nonce>` request, or null (also for plain `ping`). */
    fun pingNonce(command: String): String? {
        if (!command.startsWith("$PING ")) return null
        return command.substring(PING.length + 1).takeIf { NONCE.matches(it) }
    }

    /** Maps a requested command line to a fixed argv, or null if not allowed. `ping` returns null: see [isPing]. */
    fun toArgv(command: String): List<String>? {
        if (command.isEmpty() || command.length > HelperProtocol.MAX_REQUEST_CHARS) return null
        if (!ALLOWED_CHARS.matches(command)) return null
        val t = command.split(' ')
        if (t.any { it.isEmpty() }) return null // leading, trailing or repeated spaces
        return when {
            t.size == 6 && t[0] == "pm" && t[1] == "compile" && t[2] == "-m" && t[4] == "-f" ->
                compile(listOf("/system/bin/pm", "compile"), mode = t[3], pkg = t[5])
            t.size == 7 && t[0] == "cmd" && t[1] == "package" && t[2] == "compile" && t[3] == "-m" && t[5] == "-f" ->
                compile(listOf("/system/bin/cmd", "package", "compile"), mode = t[4], pkg = t[6])
            t.size == 3 && t[0] == "dumpsys" && t[1] == "package" && t[2] == "dexopt" ->
                listOf("/system/bin/dumpsys", "package", "dexopt")
            t.size == 3 && t[0] == "dumpsys" && t[1] == "package" ->
                pkg(t[2])?.let { listOf("/system/bin/dumpsys", "package", it) }
            else -> null
        }
    }

    private fun compile(prefix: List<String>, mode: String, pkg: String): List<String>? {
        if (mode !in MODES) return null
        val p = pkg(pkg) ?: return null
        return prefix + listOf("-m", mode, "-f", p)
    }

    /** A validated package name, and only if validation didn't change it (PackageName.parse trims). */
    private fun pkg(raw: String): String? = PackageName.parse(raw)?.value?.takeIf { it == raw }
}
