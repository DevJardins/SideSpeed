package io.github.devjardins.sidespeed.companion.l10n

/** Whole numbers with no grouping, in the locale's digits: "1234567", never "1,234,567". */
expect fun formatInteger(n: Long, localeTag: String): String

/** Numbers with exactly [digits] fraction digits and no grouping: "1.9", "2.0", "1,9". */
expect fun formatDecimal(x: Double, digits: Int, localeTag: String): String

/** BCP-47 tag of the device default locale, for number formatting outside composition. */
expect fun currentLocaleTag(): String
