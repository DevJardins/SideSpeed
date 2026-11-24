package io.github.devjardins.sidespeed.companion.ui

import io.github.devjardins.sidespeed.companion.store.Reach
import io.github.devjardins.sidespeed.companion.store.TvStatus
import io.github.devjardins.sidespeed.companion.testing.english
import io.github.devjardins.sidespeed.theme.SsTokens
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
        assertEquals(Ss.glowTrust, glowColor(Glow.ORANGE))
        assertNull(glowColor(null))
    }

    @Test fun trustDotUsesTheOrangeToken() {
        assertEquals(0xFFD97757, SsTokens.GLOW_TRUST)
        assertTrue(SsTokens.GLOW_TRUST != SsTokens.WARN_PILL_TEXT)
        assertTrue(SsTokens.GLOW_TRUST != SsTokens.GLOW_UNREACHABLE)
        assertEquals(Ss.glowTrust, statusDotColor(StatusKind.TRUST))
    }

    @Test fun trustAttentionIsOrangeWhenTheTvCanBeCheckedHere() {
        for (attention in trustAttentions) {
            for (reach in listOf(null, Reach.REACHED_LAN, Reach.REACHED_TAILNET, Reach.UNREACHABLE)) {
                assertEquals(Glow.ORANGE, cardGlow(reach, checkableHere = true, attention), "$attention $reach")
            }
            assertEquals(StatusKind.TRUST, cardDot(TvStatus.NEEDS_ATTENTION, checkableHere = true, attention), attention.name)
            assertEquals(StatusKind.TRUST, cardDot(TvStatus.RUNNING, checkableHere = true, attention), attention.name)
        }
    }

    @Test fun wirelessOffAndNoneKeepTodaysGlowAndDot() {
        for (attention in listOf(TvAttention.NONE, TvAttention.WIRELESS_OFF)) {
            assertEquals(Glow.GREEN, cardGlow(Reach.REACHED_LAN, true, attention), attention.name)
            assertEquals(Glow.BLUE, cardGlow(Reach.REACHED_TAILNET, true, attention), attention.name)
            assertEquals(Glow.RED, cardGlow(Reach.UNREACHABLE, true, attention), attention.name)
            assertNull(cardGlow(null, true, attention), attention.name)
            assertEquals(StatusKind.GOOD, cardDot(TvStatus.RUNNING, true, attention), attention.name)
            assertEquals(StatusKind.WAITING, cardDot(TvStatus.NEEDS_ATTENTION, true, attention), attention.name)
            assertEquals(StatusKind.BAD, cardDot(TvStatus.OFFLINE, true, attention), attention.name)
            assertEquals(StatusKind.UNKNOWN, cardDot(TvStatus.UNKNOWN, true, attention), attention.name)
        }
    }

    @Test fun parkedGivesRedAheadOfATrustChoice() {
        for (attention in TvAttention.entries) {
            assertEquals(Glow.RED, cardGlow(Reach.REACHED_LAN, true, attention, parked = true), attention.name)
            assertEquals(Glow.RED, cardGlow(null, true, attention, parked = true), attention.name)
            assertEquals(StatusKind.BAD, cardDot(TvStatus.RUNNING, true, attention, parked = true), attention.name)
            assertEquals(StatusKind.BAD, cardDot(TvStatus.CHECK_FAILED, true, attention, parked = true), attention.name)
        }
    }

    @Test fun notCheckableHereHasNoGlowAndAGreyDot() {
        for (attention in TvAttention.entries) {
            assertNull(cardGlow(Reach.UNREACHABLE, false, attention, parked = true), attention.name)
            assertNull(cardGlow(Reach.REACHED_LAN, false, attention), attention.name)
            assertEquals(StatusKind.UNKNOWN, cardDot(TvStatus.RUNNING, false, attention, parked = true), attention.name)
            assertEquals(StatusKind.UNKNOWN, cardDot(TvStatus.NEEDS_ATTENTION, false, attention), attention.name)
        }
    }

    @Test fun aTrustCardSaysWaitingForYou() {
        for (attention in trustAttentions) {
            val line = cardCheckedLine(attention, checkableHere = true, checkedAtEpochMs = 0L, nowMs = 60_000L)
            assertEquals(Copy.WAITING_FOR_YOU, line, attention.name)
            assertEquals("Waiting for you", line!!.english())
            assertFalse(line.english().contains("Checked"), line.english())
            assertEquals(
                Copy.WAITING_FOR_YOU,
                cardCheckedLine(attention, checkableHere = true, checkedAtEpochMs = null, nowMs = 0L),
                attention.name,
            )
        }
    }

    @Test fun otherCardsStillSayWhenTheyWereChecked() {
        assertEquals("Checked 2 min ago", cardCheckedLine(TvAttention.NONE, true, 0L, 120_000L)!!.english())
        assertEquals("Checked 0 min ago", cardCheckedLine(TvAttention.WIRELESS_OFF, true, 60_000L, 90_000L)!!.english())
        assertEquals("Checked 1 min ago", cardCheckedLine(TvAttention.CONFIRM, checkableHere = false, 0L, 60_000L)!!.english())
        assertEquals("Checked 1 min ago", cardCheckedLine(TvAttention.CONFIRM, true, 0L, 60_000L, parked = true)!!.english())
        assertNull(cardCheckedLine(TvAttention.NONE, true, checkedAtEpochMs = null, nowMs = 0L))
    }

    private val trustAttentions = listOf(TvAttention.CONFIRM, TvAttention.LEGACY_ACK, TvAttention.LEGACY_PAIR)
}
