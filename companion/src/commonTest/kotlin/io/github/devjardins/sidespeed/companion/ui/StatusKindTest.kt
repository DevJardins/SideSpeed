package io.github.devjardins.sidespeed.companion.ui

import io.github.devjardins.sidespeed.companion.store.Reach
import io.github.devjardins.sidespeed.companion.store.TvStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class StatusKindTest {
    @Test fun runningIsGood() = assertEquals(StatusKind.GOOD, statusKind(TvStatus.RUNNING))
    @Test fun waitingForAllowIsWaiting() = assertEquals(StatusKind.WAITING, statusKind(TvStatus.WAITING_FOR_ALLOW))
    @Test fun unknownIsUnknown() = assertEquals(StatusKind.UNKNOWN, statusKind(TvStatus.UNKNOWN))

    @Test fun failuresAreBad() {
        listOf(TvStatus.NOT_RUNNING, TvStatus.OFFLINE, TvStatus.NOT_INSTALLED, TvStatus.WRONG_DEVICE, TvStatus.START_FAILED)
            .forEach { assertEquals(StatusKind.BAD, statusKind(it), it.name) }
    }

    @Test fun onlyRunningIsGood() {
        TvStatus.entries.filter { it != TvStatus.RUNNING }
            .forEach { assertEquals(false, statusKind(it) == StatusKind.GOOD, it.name) }
    }

    @Test fun checkableRunningIsGood() = assertEquals(StatusKind.GOOD, cardDot(TvStatus.RUNNING, checkableHere = true))

    @Test fun uncheckableRunningIsGrey() = assertEquals(StatusKind.UNKNOWN, cardDot(TvStatus.RUNNING, checkableHere = false))

    @Test fun uncheckableNotRunningIsGrey() = assertEquals(StatusKind.UNKNOWN, cardDot(TvStatus.NOT_RUNNING, checkableHere = false))

    @Test fun lanReachGlowsGreen() = assertEquals(Glow.GREEN, cardGlow(Reach.REACHED_LAN, checkableHere = true))

    @Test fun tailnetReachGlowsBlue() = assertEquals(Glow.BLUE, cardGlow(Reach.REACHED_TAILNET, checkableHere = true))

    @Test fun unreachableReachGlowsRed() = assertEquals(Glow.RED, cardGlow(Reach.UNREACHABLE, checkableHere = true))

    @Test fun neverCheckedHasNoGlow() = assertNull(cardGlow(null, checkableHere = true))

    @Test fun uncheckableReachHasNoGlow() {
        listOf(null, Reach.REACHED_LAN, Reach.REACHED_TAILNET, Reach.UNREACHABLE)
            .forEach { assertNull(cardGlow(it, checkableHere = false), "$it") }
    }

    @Test fun glowMapsToTheThemeColours() {
        assertEquals(Ss.glowLan, glowColor(Glow.GREEN))
        assertEquals(Ss.glowTailnet, glowColor(Glow.BLUE))
        assertEquals(Ss.glowUnreachable, glowColor(Glow.RED))
        assertNull(glowColor(null))
    }
}
