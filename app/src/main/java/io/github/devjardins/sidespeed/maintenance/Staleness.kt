package io.github.devjardins.sidespeed.maintenance

import androidx.annotation.StringRes
import io.github.devjardins.sidespeed.R
import io.github.devjardins.sidespeed.apps.AppEntry
import io.github.devjardins.sidespeed.compile.CompileMode
import io.github.devjardins.sidespeed.data.LastCompile
import io.github.devjardins.sidespeed.status.StatusBadge
import io.github.devjardins.sidespeed.ui.text.UiText

/** An app is stale when it was never compiled, or was updated after its last compile started. */
fun isStale(app: AppEntry, last: LastCompile?): Boolean = last == null || app.lastUpdateTime > last.epochMs

/**
 * Stale by time, or by compile filter: the app's target is its last-used mode (speed when never compiled),
 * and a known filter below that target (speed-profile or worse for speed; worse than speed-profile for speed-profile) needs a compile.
 */
fun needsCompile(app: AppEntry, last: LastCompile?, badge: StatusBadge?): Boolean {
    if (isStale(app, last)) return true
    val target = last?.mode ?: CompileMode.SPEED
    return when (badge) {
        StatusBadge.NEEDS_RECOMPILE -> true
        StatusBadge.SPEED_PROFILE -> target == CompileMode.SPEED
        else -> false
    }
}

enum class JobMessage(@StringRes val res: Int) {
    SUCCESS(R.string.maint_job_success),
    FAILURE(R.string.maint_job_failure),
    CONNECTION_LOST(R.string.maint_job_connection_lost),
    ERROR(R.string.maint_job_error),
    ;

    fun text(detail: String?): UiText =
        if (!detail.isNullOrEmpty()) UiText.Raw(detail) else UiText.Res(res)
}

data class JobRecord(
    val pkg: String,
    val label: String,
    val epochMs: Long,
    val success: Boolean,
    val message: String,
    val messageKey: String? = null,
    val detail: String? = null,
) {
    val display: UiText
        get() = messageKey
            ?.let { key -> JobMessage.entries.firstOrNull { it.name == key }?.text(detail) }
            ?: UiText.Raw(message)
}

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
    list.joinToString("\n") {
        listOf(
            esc(it.pkg),
            esc(it.label),
            it.epochMs,
            it.success,
            esc(it.message),
            esc(it.messageKey.orEmpty()),
            esc(it.detail.orEmpty()),
        ).joinToString("|")
    }

/** Malformed lines are skipped. Accepts both the legacy 5-field form and the 7-field form. */
fun decodeHistory(raw: String): List<JobRecord> = raw.lineSequence().mapNotNull { line ->
    val f = line.split("|")
    if (f.size != 5 && f.size != 7) return@mapNotNull null
    val ms = f[2].toLongOrNull() ?: return@mapNotNull null
    val ok = f[3].toBooleanStrictOrNull() ?: return@mapNotNull null
    val key = if (f.size == 7) unesc(f[5]).ifEmpty { null } else null
    val detail = if (f.size == 7) unesc(f[6]).ifEmpty { null } else null
    JobRecord(unesc(f[0]), unesc(f[1]), ms, ok, unesc(f[4]), key, detail)
}.toList()
