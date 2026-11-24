package io.github.devjardins.sidespeed.companion.tv

import io.github.devjardins.sidespeed.companion.resources.Res
import io.github.devjardins.sidespeed.companion.resources.activity_last_day_ago
import io.github.devjardins.sidespeed.companion.resources.activity_last_hour_ago
import io.github.devjardins.sidespeed.companion.resources.activity_last_just_now
import io.github.devjardins.sidespeed.companion.resources.activity_last_min_ago
import io.github.devjardins.sidespeed.companion.ui.text.UiText

/** SideSpeed's own package on the TV: its self-compile shows up as a normal compiling row. */
const val SIDESPEED_PACKAGE = "io.github.devjardins.sidespeed"

/** What the Activity block on a TV card shows. */
enum class ActivityPhase { COMPILING, IDLE, INTERRUPTED, WAITING_HELPER, UNAVAILABLE, NEEDS_UPDATE }

/** Whether SideSpeed itself is optimized. DEBUG hides the Self row entirely. */
enum class SelfState { OPTIMIZED, PENDING, DEBUG }

/**
 * One parsed `content query .../status/now` row. Empty strings and nulls mean absent,
 * mirroring the provider's empty-when-absent columns.
 */
data class TvActivity(
    val phase: ActivityPhase,
    val pkg: String = "",
    val label: String = "",
    val mode: String = "",
    val startedMs: Long = 0L,
    val queue: Int = 0,
    val lastPkg: String = "",
    val lastLabel: String = "",
    val lastOk: Boolean? = null,
    val lastMs: Long = 0L,
    val self: SelfState = SelfState.PENDING,
    val version: String = "",
) {
    /** The running compile is SideSpeed optimizing itself: the Self row shows a spinner. */
    val selfCompiling: Boolean get() = phase == ActivityPhase.COMPILING && pkg == SIDESPEED_PACKAGE
}

/**
 * Parses `content query` stdout. Returns null when there is no usable row (blank output,
 * "No result found.", malformed numbers, missing columns, unknown `state`) so the caller
 * keeps the previous state instead of flashing. A missing provider (old SideSpeed) returns
 * [ActivityPhase.NEEDS_UPDATE] instead of null, so the update caption shows steadily.
 */
fun parseActivityRow(output: String): TvActivity? {
    if (output.contains("Could not find provider") || output.contains("Error while accessing provider")) {
        return TvActivity(ActivityPhase.NEEDS_UPDATE)
    }
    val line = output.lineSequence().map { it.trim() }.firstOrNull { it.startsWith("Row:") } ?: return null
    // "Row: 0 v=1, ...": drop the row index, keep the pairs.
    val body = line.substringAfter("Row:", "").trim().substringAfter(" ", "").ifEmpty { return null }
    val values = LinkedHashMap<String, String>()
    for (pair in body.split(", ")) {
        val cut = pair.indexOf('=')
        if (cut < 0) return null
        values[pair.substring(0, cut)] = pair.substring(cut + 1)
    }
    if (values["v"] != "1") return null
    val phase = when (values["state"]) {
        "compiling" -> ActivityPhase.COMPILING
        "idle" -> ActivityPhase.IDLE
        "interrupted" -> ActivityPhase.INTERRUPTED
        "waiting_helper" -> ActivityPhase.WAITING_HELPER
        else -> return null
    }
    val lastResult = values["last_result"] ?: return null
    return TvActivity(
        phase = phase,
        pkg = values["pkg"] ?: return null,
        label = decodeStatusLabel(values["label"] ?: return null),
        mode = values["mode"] ?: return null,
        startedMs = values["started_ms"]?.ifEmpty { "0" }?.toLongOrNull() ?: return null,
        queue = values["queue"]?.toIntOrNull() ?: return null,
        lastPkg = values["last_pkg"] ?: return null,
        lastLabel = decodeStatusLabel(values["last_label"] ?: return null),
        lastOk = when (lastResult) {
            "ok" -> true
            "failed" -> false
            "" -> null
            else -> return null
        },
        lastMs = values["last_ms"]?.ifEmpty { "0" }?.toLongOrNull() ?: return null,
        self = when (values["self"]) {
            "optimized" -> SelfState.OPTIMIZED
            "debug" -> SelfState.DEBUG
            "pending", "" -> SelfState.PENDING
            else -> return null
        },
        version = values["version"] ?: return null,
    )
}

/** Inverse of the provider's label escaping: a single left-to-right pass, so `%2550` is `%50`. */
fun decodeStatusLabel(raw: String): String {
    if (!raw.contains('%')) return raw
    val out = StringBuilder(raw.length)
    var i = 0
    while (i < raw.length) {
        val c = raw[i]
        if (c == '%' && i + 2 < raw.length + 1) {
            val hex = raw.substring(i + 1, (i + 3).coerceAtMost(raw.length))
            val byte = hex.toIntOrNull(16)
            if (byte != null && hex.length == 2) {
                out.append(byte.toChar())
                i += 3
                continue
            }
        }
        out.append(c)
        i++
    }
    return out.toString()
}

/** Elapsed time as `m:ss` for the compiling row; ticks every second from `started_ms`. */
fun formatActivityElapsed(elapsedMs: Long): String {
    val totalSeconds = (elapsedMs.coerceAtLeast(0L) / 1000L)
    return "${totalSeconds / 60}:${(totalSeconds % 60).toString().padStart(2, '0')}"
}

/** `"Last: <label> · 3 min ago"`, or null when there is no finished compile to show. */
fun lastCaption(label: String, lastMs: Long, nowMs: Long, labelIsLtr: Boolean = false): UiText? {
    if (label.isEmpty() || lastMs <= 0L) return null
    val diff = (nowMs - lastMs).coerceAtLeast(0L)
    val labelArg: UiText = if (labelIsLtr) UiText.Ltr(label) else UiText.Raw(label)
    return when {
        diff < 60_000L -> UiText.Res(Res.string.activity_last_just_now, listOf(labelArg))
        diff < 3_600_000L -> {
            val mins = (diff / 60_000L).toInt()
            UiText.Plural(Res.plurals.activity_last_min_ago, mins, listOf(labelArg, mins))
        }
        diff < 86_400_000L -> {
            val hours = (diff / 3_600_000L).toInt()
            UiText.Plural(Res.plurals.activity_last_hour_ago, hours, listOf(labelArg, hours))
        }
        else -> {
            val days = (diff / 86_400_000L).toInt()
            UiText.Plural(Res.plurals.activity_last_day_ago, days, listOf(labelArg, days))
        }
    }
}
