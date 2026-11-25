package io.github.devjardins.sidespeed.companion.l10n

import kotlin.test.Test
import kotlin.test.assertEquals

class NumbersTest {
    @Test fun english_unchanged() {
        assertEquals("1.9", formatDecimal(1.9, 1, "en-US"))
        assertEquals("12", formatInteger(12, "en-US"))
    }

    @Test fun integers_never_grouped() {
        assertEquals("1234567", formatInteger(1234567, "en-US"))
        assertEquals("1234567", formatInteger(1234567, "de-DE"))
    }

    @Test fun german_decimal_comma() {
        assertEquals("1,9", formatDecimal(1.9, 1, "de-DE"))
    }

    @Test fun arabic_digits() {
        assertEquals("٣٥", formatInteger(35, "ar-u-nu-arab"))
    }
}
