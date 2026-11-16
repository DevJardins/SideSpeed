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
