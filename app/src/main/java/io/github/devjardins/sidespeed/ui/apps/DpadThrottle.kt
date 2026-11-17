package io.github.devjardins.sidespeed.ui.apps

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type

/**
 * Whether a D-pad KeyDown at [eventTimeMs] should be handled given the last handled one at
 * [lastHandledAtMs] (null when nothing was handled yet). A single press ([repeatCount] == 0) is never
 * dropped; a repeat inside [windowMs] of the last handled event is dropped. Returns the new
 * last-handled time when the event should be handled, null when it should be dropped.
 */
fun nextDpadHandledAt(lastHandledAtMs: Long?, eventTimeMs: Long, repeatCount: Int, windowMs: Long = 60L): Long? {
    if (repeatCount == 0) return eventTimeMs
    if (lastHandledAtMs == null || eventTimeMs - lastHandledAtMs >= windowMs) return eventTimeMs
    return null
}

/**
 * Drops held-D-pad key repeats ([KeyEventType.KeyDown] on [Key.DirectionUp]/[Key.DirectionDown] with a
 * native repeat count above zero) that arrive within [windowMs] of the last handled one, so repeats
 * cannot outrun the scroll animation and queue up. A single press always passes through.
 */
@Composable
fun rememberDpadRepeatThrottle(windowMs: Long = 60L): Modifier {
    var lastHandledAtMs by remember { mutableStateOf<Long?>(null) }
    return Modifier.onPreviewKeyEvent { e ->
        if (e.type == KeyEventType.KeyDown && (e.key == Key.DirectionUp || e.key == Key.DirectionDown)) {
            val next = nextDpadHandledAt(lastHandledAtMs, e.nativeKeyEvent.eventTime, e.nativeKeyEvent.repeatCount, windowMs)
            if (next == null) true else {
                lastHandledAtMs = next
                false
            }
        } else {
            false
        }
    }
}
