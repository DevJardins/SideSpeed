package io.github.devjardins.sidespeed.companion.l10n

import java.text.NumberFormat
import java.util.Locale

private fun localeFor(tag: String): Locale = Locale.forLanguageTag(tag)

actual fun formatInteger(n: Long, localeTag: String): String {
    val format = NumberFormat.getIntegerInstance(localeFor(localeTag))
    format.isGroupingUsed = false
    return format.format(n)
}

actual fun formatDecimal(x: Double, digits: Int, localeTag: String): String {
    val format = NumberFormat.getNumberInstance(localeFor(localeTag))
    format.isGroupingUsed = false
    format.minimumFractionDigits = digits
    format.maximumFractionDigits = digits
    return format.format(x)
}

actual fun currentLocaleTag(): String = Locale.getDefault().toLanguageTag()
