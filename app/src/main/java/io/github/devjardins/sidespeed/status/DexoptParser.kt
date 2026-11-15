package io.github.devjardins.sidespeed.status

/** ART compiler filter of a package; [filter] is null when the package or its filter was not found. */
data class CompilerStatus(val filter: String?)

enum class StatusBadge { SPEED_OK, SPEED_PROFILE, NEEDS_RECOMPILE, UNKNOWN }

object DexoptParser {
    private val statusRegex = Regex("""status=([A-Za-z-]+)""")
    private val filterRegex = Regex("""compilation_filter=([a-z-]+)""")
    private const val SECONDARY_MARKER = "known secondary dex files"

    fun parse(dump: String, pkg: String): CompilerStatus {
        val lines = dump.lines()
        val header = "[$pkg]"
        // `dumpsys package <pkg>` repeats "[pkg]" in several sections (key sets, dexopt state, compiler stats);
        // the first block that carries any status/filter text is the dexopt block.
        for (start in lines.indices) {
            if (lines[start].trim() != header) continue
            parseBlock(lines, start)?.let { return it }
        }
        return CompilerStatus(null)
    }

    /** Null when the block at [start] mentions no status at all, so the caller can try the next header. */
    private fun parseBlock(lines: List<String>, start: Int): CompilerStatus? {
        val indent = indentOf(lines[start])
        val filters = mutableListOf<String>()
        var mentioned = false
        var unresolved = false
        for (i in start + 1 until lines.size) {
            val line = lines[i]
            if (line.trimStart().startsWith("[") && indentOf(line) == indent) break
            if (line.contains(SECONDARY_MARKER)) break
            if (!line.contains("status=") && !line.contains("compilation_filter")) continue
            mentioned = true
            val filter = filterOf(line)
            if (filter == null) unresolved = true else filters += filter
        }
        if (!mentioned) return null
        // A line we cannot read could be the worst ABI, so the honest answer is "unknown".
        if (unresolved) return CompilerStatus(null)
        // Lowest wins so a half-compiled app never shows "speed"; minBy keeps the first on ties.
        return CompilerStatus(filters.minByOrNull { rank(it) })
    }

    fun badge(status: CompilerStatus): StatusBadge = when (status.filter) {
        null -> StatusBadge.UNKNOWN
        "speed" -> StatusBadge.SPEED_OK
        "speed-profile" -> StatusBadge.SPEED_PROFILE
        else -> StatusBadge.NEEDS_RECOMPILE
    }

    private fun indentOf(line: String) = line.length - line.trimStart().length

    /** First non-oat-state `status=` token (those start with 'k', e.g. kOatUpToDate), else `compilation_filter=`. */
    private fun filterOf(line: String): String? =
        statusRegex.findAll(line).map { it.groupValues[1] }.firstOrNull { !it.startsWith("k") }
            ?: filterRegex.find(line)?.groupValues?.get(1)

    private fun rank(filter: String) = when (filter) {
        "speed" -> 2
        "speed-profile" -> 1
        else -> 0
    }
}
