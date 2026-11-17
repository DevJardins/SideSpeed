package io.github.devjardins.sidespeed.ui.apps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DpadThrottleTest {
    @Test fun singlePressIsAlwaysHandled() {
        // Never handled before.
        assertEquals(1000L, nextDpadHandledAt(null, 1000L, 0))
        // Even immediately after another handled press.
        assertEquals(1000L, nextDpadHandledAt(1000L, 1000L, 0))
        assertEquals(1010L, nextDpadHandledAt(1000L, 1010L, 0))
    }

    @Test fun firstRepeatIsHandled() {
        assertEquals(1000L, nextDpadHandledAt(null, 1000L, 1))
    }

    @Test fun repeatInsideWindowIsDropped() {
        assertNull(nextDpadHandledAt(1000L, 1059L, 3))
        assertNull(nextDpadHandledAt(1000L, 1001L, 1))
    }

    @Test fun repeatAtWindowEdgeIsHandled() {
        assertEquals(1060L, nextDpadHandledAt(1000L, 1060L, 2))
        assertEquals(1200L, nextDpadHandledAt(1000L, 1200L, 9))
    }

    @Test fun customWindowIsRespected() {
        assertNull(nextDpadHandledAt(1000L, 1099L, 1, windowMs = 100L))
        assertEquals(1100L, nextDpadHandledAt(1000L, 1100L, 1, windowMs = 100L))
    }

    @Test fun heldKeySequenceKeepsSixtyFpsPace() {
        // Press at t, then repeats every 16 ms: only the repeats at least 60 ms apart pass through.
        var last: Long? = nextDpadHandledAt(null, 0L, 0)
        val handled = mutableListOf<Long>()
        var t = 16L
        while (t <= 200L) {
            val next = nextDpadHandledAt(last, t, 1)
            if (next != null) {
                handled += next
                last = next
            }
            t += 16L
        }
        handled.forEach { assertEquals(0L, it % 64L) }
    }
}
