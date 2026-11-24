package io.github.devjardins.sidespeed.companion.ui

import io.github.devjardins.sidespeed.companion.store.Reach
import io.github.devjardins.sidespeed.companion.store.TvStatus

/**
 * Which colour a TV card glows: green on the Trusted Network, blue over Tailscale,
 * orange while it waits for trust, red when unreachable.
 */
enum class Glow { GREEN, BLUE, ORANGE, RED }

/** Confirm, the older-connection acknowledgment, or the offer to pair. */
private fun waitsForTrust(attention: TvAttention): Boolean = when (attention) {
    TvAttention.CONFIRM, TvAttention.LEGACY_ACK, TvAttention.LEGACY_PAIR -> true
    TvAttention.WIRELESS_OFF, TvAttention.NONE -> false
}

/**
 * The glow a TV card shows. A TV that cannot be checked from here keeps the plain border.
 * A parked TV (failed proof) is red, ahead of a trust choice. A trust choice that can be
 * checked here is a steady orange. Otherwise this is how the last check reached it, or null
 * when it has not been checked yet.
 */
fun cardGlow(
    reach: Reach?,
    checkableHere: Boolean,
    attention: TvAttention = TvAttention.NONE,
    parked: Boolean = false,
): Glow? {
    if (!checkableHere) return null
    if (parked) return Glow.RED
    if (waitsForTrust(attention)) return Glow.ORANGE
    return when (reach) {
        Reach.REACHED_LAN -> Glow.GREEN
        Reach.REACHED_TAILNET -> Glow.BLUE
        Reach.UNREACHABLE -> Glow.RED
        null -> null
    }
}

/** How a TV's status reads at a glance: drives the status dot colour. */
enum class StatusKind { GOOD, WAITING, TRUST, BAD, UNKNOWN }

fun statusKind(s: TvStatus): StatusKind = when (s) {
    TvStatus.RUNNING -> StatusKind.GOOD
    TvStatus.WAITING_FOR_ALLOW, TvStatus.NEEDS_ATTENTION, TvStatus.CHECK_FAILED -> StatusKind.WAITING
    TvStatus.NOT_RUNNING, TvStatus.OFFLINE, TvStatus.NOT_INSTALLED, TvStatus.WRONG_DEVICE, TvStatus.START_FAILED -> StatusKind.BAD
    TvStatus.UNKNOWN -> StatusKind.UNKNOWN
}

/**
 * The dot a TV card shows. Grey ([StatusKind.UNKNOWN]) when the TV cannot be checked from here,
 * so a saved green never looks live off its network. A parked TV is the red failure dot, ahead of
 * a trust choice. A trust choice that can be checked here is orange ([StatusKind.TRUST]).
 * Otherwise this is the live status colour.
 */
fun cardDot(
    status: TvStatus,
    checkableHere: Boolean,
    attention: TvAttention = TvAttention.NONE,
    parked: Boolean = false,
): StatusKind {
    if (!checkableHere) return StatusKind.UNKNOWN
    if (parked) return StatusKind.BAD
    if (waitsForTrust(attention)) return StatusKind.TRUST
    return statusKind(status)
}

/**
 * The line under a TV's status. A trust choice that can be checked here says [Copy.WAITING_FOR_YOU].
 * Every other card says when it was checked, when a time is saved.
 */
fun cardCheckedLine(
    attention: TvAttention,
    checkableHere: Boolean,
    checkedAtEpochMs: Long?,
    nowMs: Long,
    parked: Boolean = false,
): String? {
    if (checkableHere && !parked && waitsForTrust(attention)) return Copy.WAITING_FOR_YOU
    val at = checkedAtEpochMs ?: return null
    return Copy.checkedAgo(nowMs - at)
}
