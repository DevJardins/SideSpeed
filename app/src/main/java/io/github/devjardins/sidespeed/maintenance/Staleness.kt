package io.github.devjardins.sidespeed.maintenance

import io.github.devjardins.sidespeed.apps.AppEntry
import io.github.devjardins.sidespeed.data.LastCompile

/** An app is stale when it was never compiled, or was updated after its last compile started. */
fun isStale(app: AppEntry, last: LastCompile?): Boolean = last == null || app.lastUpdateTime > last.epochMs

data class JobRecord(
    val pkg: String,
    val label: String,
    val epochMs: Long,
    val success: Boolean,
    val message: String,
)

const val HISTORY_LIMIT = 20

/** Newest first, capped at [HISTORY_LIMIT]. */
fun appendHistory(history: List<JobRecord>, record: JobRecord): List<JobRecord> =
    (listOf(record) + history).take(HISTORY_LIMIT)

private fun esc(s: String) = buildString {
    for (c in s) when (c) {
        '%' -> append("%25")
        '|' -> append("%7C")
        '\n' -> append("%0A")
        '\r' -> append("%0D")
        else -> append(c)
    }
}

private fun unesc(s: String) = s
    .replace("%7C", "|").replace("%0A", "\n").replace("%0D", "\r").replace("%25", "%")

/** One record per line, fields percent-escaped and joined by `|`. */
fun encodeHistory(list: List<JobRecord>): String =
    list.joinToString("\n") { listOf(esc(it.pkg), esc(it.label), it.epochMs, it.success, esc(it.message)).joinToString("|") }

/** Malformed lines are skipped. */
fun decodeHistory(raw: String): List<JobRecord> = raw.lineSequence().mapNotNull { line ->
    val f = line.split("|")
    if (f.size != 5) return@mapNotNull null
    val ms = f[2].toLongOrNull() ?: return@mapNotNull null
    val ok = f[3].toBooleanStrictOrNull() ?: return@mapNotNull null
    JobRecord(unesc(f[0]), unesc(f[1]), ms, ok, unesc(f[4]))
}.toList()
