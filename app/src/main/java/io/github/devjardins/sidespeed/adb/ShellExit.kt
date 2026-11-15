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

    /**
     * Splits a raw output line around the marker, which can land mid-line when the
     * command's output lacks a trailing newline. Returns (text before marker, exit code);
     * text is null when empty, code is null when the line carries no parsable marker.
     */
    fun split(line: String): Pair<String?, Int?> {
        val idx = line.indexOf(MARKER)
        if (idx < 0) return line to null
        val code = line.substring(idx + MARKER.length).trim().toIntOrNull()
            ?: return line to null
        return line.substring(0, idx).ifEmpty { null } to code
    }
}
