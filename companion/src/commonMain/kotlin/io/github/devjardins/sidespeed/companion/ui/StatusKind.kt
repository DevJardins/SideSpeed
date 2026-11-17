package io.github.devjardins.sidespeed.companion.ui

import io.github.devjardins.sidespeed.companion.store.TvStatus

/** How a TV's status reads at a glance: drives the status dot colour. */
enum class StatusKind { GOOD, WAITING, BAD, UNKNOWN }

fun statusKind(s: TvStatus): StatusKind = when (s) {
    TvStatus.RUNNING -> StatusKind.GOOD
    TvStatus.WAITING_FOR_ALLOW -> StatusKind.WAITING
    TvStatus.NOT_RUNNING, TvStatus.OFFLINE, TvStatus.NOT_INSTALLED, TvStatus.WRONG_DEVICE, TvStatus.START_FAILED -> StatusKind.BAD
    TvStatus.UNKNOWN -> StatusKind.UNKNOWN
}

/**
 * The dot a TV card shows: the live status colour when the TV can be checked from here,
 * grey ([StatusKind.UNKNOWN]) when it can't, so a saved green never looks live off its network.
 */
fun cardDot(status: TvStatus, checkableHere: Boolean): StatusKind =
    if (checkableHere) statusKind(status) else StatusKind.UNKNOWN
