package io.github.devjardins.sidespeed.companion.l10n

import io.github.devjardins.sidespeed.l10n.matchShipped

/**
 * Debug pseudolocale tags, set once at startup by each platform (Android debug only).
 * The picker and the shown language both build on [shippedLanguages], so this one
 * list keeps them in agreement.
 */
var debugLanguageTags: List<String> = emptyList()

/** The tags the picker offers: shipped translations plus the debug pseudolocales. */
fun shippedLanguages(): List<String> = ShippedLanguages.TAGS + debugLanguageTags

/**
 * The tag the app actually shows for [requestedTag]: the matched shipped language,
 * else US English. Numbers and layout direction follow this, never the raw request.
 */
fun effectiveLanguageTag(requestedTag: String, shipped: List<String>): String =
    matchShipped(requestedTag, shipped) ?: "en-US"

/** The tag the app actually shows now, from the device default locale. */
fun effectiveLanguageTag(requestedTag: String = currentLocaleTag()): String =
    effectiveLanguageTag(requestedTag, shippedLanguages())
