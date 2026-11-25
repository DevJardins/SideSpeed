package io.github.devjardins.sidespeed.companion.l10n

import kotlin.test.Test
import kotlin.test.assertEquals

class EffectiveLanguageTest {
    @Test
    fun dropped_language_maps_to_us_english() {
        assertEquals("en-US", effectiveLanguageTag("ar", emptyList()))
    }

    @Test
    fun shipped_pseudo_tag_maps_to_itself() {
        assertEquals("ar-XB", effectiveLanguageTag("ar-XB", listOf("ar-XB")))
    }

    @Test
    fun dropped_language_formats_in_ascii_english() {
        val tag = effectiveLanguageTag("ar", emptyList())
        assertEquals("35", formatInteger(35, tag))
        assertEquals("1.9", formatDecimal(1.9, 1, tag))
    }
}
