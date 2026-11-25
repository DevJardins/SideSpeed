package io.github.devjardins.sidespeed.l10n

import java.util.Locale

actual fun canonicalLanguageTag(tag: String): String = Locale.forLanguageTag(tag).toLanguageTag()
