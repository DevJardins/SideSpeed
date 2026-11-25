package io.github.devjardins.sidespeed.ui.compile

import io.github.devjardins.sidespeed.R
import io.github.devjardins.sidespeed.compile.CompileMode
import io.github.devjardins.sidespeed.status.StatusBadge
import io.github.devjardins.sidespeed.ui.apps.pillFor
import io.github.devjardins.sidespeed.ui.text.UiText
import io.github.devjardins.sidespeed.ui.text.uiText

/** "51 s" under a minute, otherwise "2 min 58 s". */
fun formatDuration(seconds: Long): UiText {
    val s = seconds.coerceAtLeast(0)
    if (s < 60) {
        val count = s.toInt()
        return UiText.Plural(R.plurals.compile_duration_seconds, count, listOf(s))
    }
    val min = s / 60
    val rem = s % 60
    val secPart = UiText.Plural(R.plurals.compile_duration_seconds, rem.toInt(), listOf(rem))
    return UiText.Plural(R.plurals.compile_duration_minutes, min.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), listOf(min, secPart))
}

fun successTitle(mode: CompileMode): UiText =
    uiText(R.string.compile_success_title, UiText.Ltr(mode.arg))

/** The badge that matches a successful compile in [mode]. */
private fun expectedBadge(mode: CompileMode): StatusBadge = when (mode) {
    CompileMode.SPEED -> StatusBadge.SPEED_OK
    CompileMode.SPEED_PROFILE -> StatusBadge.SPEED_PROFILE
}

/**
 * "Took 2 min 58 s · status confirmed speed ✓". When the status check disagrees with the mode it says
 * "status shows …" instead of "confirmed"; with no usable badge only the duration is shown.
 */
fun successDetail(seconds: Long, mode: CompileMode, badge: StatusBadge?): UiText {
    val duration = formatDuration(seconds)
    val label = pillFor(false, badge)?.label ?: return uiText(R.string.compile_success_took, duration)
    val res = if (badge == expectedBadge(mode)) {
        R.string.compile_success_took_confirmed
    } else {
        R.string.compile_success_took_shows
    }
    return uiText(res, duration, UiText.Res(label))
}
