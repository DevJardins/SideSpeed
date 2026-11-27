package io.github.devjardins.sidespeed.companion.desktop

import io.github.devjardins.sidespeed.companion.ui.CardBounds
import org.junit.Assert.assertEquals
import org.junit.Test

class DesktopDropHitTest {
    private val card = CardBounds(48f, 200f, 792f, 500f, 2.0f)

    @Test
    fun matches_card_at_1x_and_2x_hidpi_density() {
        val two = mapOf("a" to card, "b" to CardBounds(48f, 600f, 792f, 900f, 2.0f))
        val reachable = setOf("a", "b")
        assertEquals(DropHit.Tv("a"), DesktopDropHitTester.hitTest(100.0, 150.0, two, reachable))
        assertEquals(DropHit.Miss, DesktopDropHitTester.hitTest(100.0, 300.0, mapOf("a" to card), setOf("a", "b")))
        // 1x: pixels are points.
        val one = mapOf("a" to CardBounds(24f, 100f, 396f, 250f, 1.0f), "b" to CardBounds(24f, 300f, 396f, 450f, 1.0f))
        assertEquals(DropHit.Tv("b"), DesktopDropHitTester.hitTest(100.0, 350.0, one, reachable))
        // Rejects what would match only if density were ignored.
        assertEquals(DropHit.Miss, DesktopDropHitTester.hitTest(500.0, 300.0, one + ("c" to card), setOf("a", "b")))
    }

    @Test
    fun falls_back_to_single_reachable_tv_when_dropped_outside_card_bounds() {
        val cards = mapOf("a" to card, "b" to CardBounds(48f, 600f, 792f, 900f, 2.0f))
        assertEquals(DropHit.Tv("b"), DesktopDropHitTester.hitTest(10.0, 10.0, cards, setOf("b")))
        // Over an unreachable card, still falls back to the one reachable TV.
        assertEquals(DropHit.Tv("b"), DesktopDropHitTester.hitTest(100.0, 150.0, cards, setOf("b")))
    }

    @Test
    fun returns_miss_when_multiple_reachable_tvs_and_outside_bounds() {
        val cards = mapOf("a" to card, "b" to CardBounds(48f, 600f, 792f, 900f, 2.0f))
        assertEquals(DropHit.Miss, DesktopDropHitTester.hitTest(10.0, 10.0, cards, setOf("a", "b")))
        assertEquals(DropHit.Miss, DesktopDropHitTester.hitTest(10.0, 10.0, cards, emptySet()))
    }
}
