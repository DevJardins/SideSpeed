package io.github.devjardins.sidespeed.companion.ui

import io.github.devjardins.sidespeed.companion.store.Reach
import io.github.devjardins.sidespeed.companion.store.TvStatus

/** Which colour a TV card glows: green on the home network, blue over Tailscale, red when unreachable. */
enum class Glow { GREEN, BLUE, RED }

/**
 * The glow a TV card shows for how the last check reached it, or null for today's plain border: never
 * checked, or not checkable from here (the grey last-seen state).
 */
fun cardGlow(reach: Reach?, checkableHere: Boolean): Glow? {
    if (!checkableHere) return null
    return when (reach) {
        Reach.REACHED_LAN -> Glow.GREEN
        Reach.REACHED_TAILNET -> Glow.BLUE
        Reach.UNREACHABLE -> Glow.RED
        null -> null
    }
}

/** How a TV's status reads at a glance: drives the status dot colour. */
enum class StatusKind { GOOD, WAITING, BAD, UNKNOWN }

fun statusKind(s: TvStatus): StatusKind = when (s) {
    TvStatus.RUNNING -> StatusKind.GOOD
    TvStatus.WAITING_FOR_ALLOW, TvStatus.NEEDS_ATTENTION -> StatusKind.WAITING
    TvStatus.NOT_RUNNING, TvStatus.OFFLINE, TvStatus.NOT_INSTALLED, TvStatus.WRONG_DEVICE, TvStatus.START_FAILED -> StatusKind.BAD
    TvStatus.UNKNOWN -> StatusKind.UNKNOWN
}

/**
 * The dot a TV card shows: the live status colour when the TV can be checked from here,
 * grey ([StatusKind.UNKNOWN]) when it can't, so a saved green never looks live off its network.
 */
fun cardDot(status: TvStatus, checkableHere: Boolean): StatusKind =
    if (checkableHere) statusKind(status) else StatusKind.UNKNOWN
