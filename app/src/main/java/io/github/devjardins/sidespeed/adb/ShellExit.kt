package io.github.devjardins.sidespeed.adb

/**
 * libadb-android speaks the shell v1 protocol, which reports no exit code.
 * Commands are wrapped so the shell echoes its own exit status as a marker line.
 */
object ShellExit {
    const val MARKER = "__SIDESPEED_EXIT__:"

    fun wrap(cmd: String): String = "$cmd; echo $MARKER\$?"

    fun parse(line: String): Int? {
        val trimmed = line.trim()
        if (!trimmed.startsWith(MARKER)) return null
        return trimmed.removePrefix(MARKER).toIntOrNull()
    }
}
