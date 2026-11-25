package io.github.devjardins.sidespeed.l10n

import java.util.Locale

actual fun nativeLanguageName(tag: String): String {
    val locale = Locale.forLanguageTag(tag)
    val display = locale.getDisplayName(locale)
    return display.replaceFirstChar {
        if (it.isLowerCase()) it.titlecase(locale) else it.toString()
    }
}
