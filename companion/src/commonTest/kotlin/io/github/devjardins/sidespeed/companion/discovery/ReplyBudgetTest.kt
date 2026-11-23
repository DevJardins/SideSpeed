package io.github.devjardins.sidespeed.companion.discovery

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReplyBudgetTest {
    @Test fun stopsAtMaxDatagrams() {
        val budget = ReplyBudget()
        repeat(MAX_DATAGRAMS) { assertTrue(budget.accept(1)) }
        assertFalse(budget.accept(1))
    }

    @Test fun stopsAtByteCap() {
        val budget = ReplyBudget()
        assertTrue(budget.accept(MAX_DATAGRAM_BYTES))
        assertFalse(budget.accept(1))
    }

    @Test fun replyThatWouldCrossByteCapIsDroppedAndEndsCollection() {
        val budget = ReplyBudget()
        assertTrue(budget.accept(MAX_DATAGRAM_BYTES - 1))
        assertFalse(budget.accept(2))
        assertFalse(budget.accept(0))
    }

    @Test fun zeroLengthRepliesCountTowardDatagramCap() {
        val budget = ReplyBudget(maxBytes = Int.MAX_VALUE)
        repeat(MAX_DATAGRAMS) { assertTrue(budget.accept(0)) }
        assertFalse(budget.accept(0))
    }
}