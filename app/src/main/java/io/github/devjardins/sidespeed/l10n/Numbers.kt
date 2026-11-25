package io.github.devjardins.sidespeed.l10n

import java.util.Locale

/** Whole numbers with no grouping, in the locale's digits: "1234567", never "1,234,567". */
fun formatInteger(n: Long, locale: Locale): String = String.format(locale, "%d", n)

/** Numbers with exactly [digits] fraction digits and no grouping: "1.9", "2.0", "1,9". */
fun formatDecimal(x: Double, digits: Int, locale: Locale): String = String.format(locale, "%.${digits}f", x)
