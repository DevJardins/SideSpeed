package io.github.devjardins.sidespeed.ui.compile

import io.github.devjardins.sidespeed.compile.CompileMode
import io.github.devjardins.sidespeed.status.StatusBadge
import io.github.devjardins.sidespeed.ui.apps.pillFor

/** "51 s" under a minute, otherwise "2 min 58 s". */
fun formatDuration(seconds: Long): String {
    val s = seconds.coerceAtLeast(0)
    return if (s < 60) "$s s" else "${s / 60} min ${s % 60} s"
}

fun successTitle(mode: CompileMode): String = "Compiled to ${mode.arg}"

/** The badge that matches a successful compile in [mode]. */
private fun expectedBadge(mode: CompileMode): StatusBadge = when (mode) {
    CompileMode.SPEED -> StatusBadge.SPEED_OK
    CompileMode.SPEED_PROFILE -> StatusBadge.SPEED_PROFILE
}

/**
 * "Took 2 min 58 s · status confirmed speed ✓". When the status check disagrees with the mode it says
 * "status shows …" instead of "confirmed"; with no usable badge only the duration is shown.
 */
fun successDetail(seconds: Long, mode: CompileMode, badge: StatusBadge?): String {
    val took = "Took ${formatDuration(seconds)}"
    val label = pillFor(false, badge)?.label ?: return took
    val verb = if (badge == expectedBadge(mode)) "confirmed" else "shows"
    return "$took · status $verb $label"
}
