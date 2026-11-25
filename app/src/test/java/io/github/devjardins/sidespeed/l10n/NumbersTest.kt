package io.github.devjardins.sidespeed.l10n

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class NumbersTest {
    @Test fun english_unchanged() {
        assertEquals("1.9", formatDecimal(1.9, 1, Locale.US))
        assertEquals("12", formatInteger(12, Locale.US))
    }

    @Test fun integers_never_grouped() {
        assertEquals("1234567", formatInteger(1234567, Locale.US))
        assertEquals("1234567", formatInteger(1234567, Locale.GERMANY))
    }

    @Test fun german_decimal_comma() {
        assertEquals("1,9", formatDecimal(1.9, 1, Locale.GERMANY))
    }

    @Test fun arabic_digits() {
        assertEquals("٣٥", formatInteger(35, Locale.forLanguageTag("ar-u-nu-arab")))
    }

    @Test fun dropped_language_formats_in_ascii_english() {
        val locale = AppLanguage.effectiveLocale("ar", emptyList())
        assertEquals("35", formatInteger(35, locale))
        assertEquals("1.9", formatDecimal(1.9, 1, locale))
    }
}
