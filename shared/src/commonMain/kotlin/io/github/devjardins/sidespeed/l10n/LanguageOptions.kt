package io.github.devjardins.sidespeed.l10n

data class LanguageOption(
    val tag: String?,
    val name: String?,
    val selected: Boolean,
    val notComplete: Boolean,
)

private const val ENGLISH = "en"

/** Canonical BCP 47 form, so legacy codes match what the platform reports (`iw` -> `he`). */
expect fun canonicalLanguageTag(tag: String): String

private fun languageOf(canonicalTag: String): String = canonicalTag.substringBefore('-').lowercase()

private fun scriptOf(canonicalTag: String): String? =
    canonicalTag.split('-').drop(1).firstOrNull { it.length == 4 && it.all(Char::isLetter) }?.lowercase()

/**
 * The shipped tag the app will actually show for [tag], or null when nothing ships it.
 * English always counts as shipped. Canonicalises both sides and returns the original
 * shipped tag (or "en") on an exact match, else the same language and explicit script,
 * else the same language.
 */
fun matchShipped(tag: String, shipped: List<String>): String? {
    val want = canonicalLanguageTag(tag)
    for (shippedTag in shipped) {
        if (canonicalLanguageTag(shippedTag) == want) return shippedTag
    }
    if (languageOf(want) == ENGLISH) return ENGLISH
    val wantScript = scriptOf(want)
    if (wantScript != null) {
        for (shippedTag in shipped) {
            val candidate = canonicalLanguageTag(shippedTag)
            if (languageOf(candidate) == languageOf(want) && scriptOf(candidate) == wantScript) {
                return shippedTag
            }
        }
    }
    for (shippedTag in shipped) {
        if (languageOf(canonicalLanguageTag(shippedTag)) == languageOf(want)) return shippedTag
    }
    return null
}

fun languageOptions(
    shipped: List<String>,
    chosen: String?,
    nativeName: (String) -> String,
): List<LanguageOption> {
    val matched = chosen?.let { matchShipped(it, shipped) }
    val result = mutableListOf<LanguageOption>()

    result +=
        LanguageOption(
            tag = null,
            name = null,
            selected = chosen == null,
            notComplete = false,
        )

    result +=
        LanguageOption(
            tag = ENGLISH,
            name = nativeName(ENGLISH),
            selected = matched == ENGLISH,
            notComplete = false,
        )

    val rest =
        shipped
            .filter { it != ENGLISH }
            .sortedBy { nativeName(it).lowercase() }
    for (tag in rest) {
        result +=
            LanguageOption(
                tag = tag,
                name = nativeName(tag),
                selected = tag == matched,
                notComplete = false,
            )
    }

    if (chosen != null && matched == null) {
        result +=
            LanguageOption(
                tag = chosen,
                name = nativeName(chosen),
                selected = true,
                notComplete = true,
            )
    }

    return result
}
