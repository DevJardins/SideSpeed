package io.github.devjardins.sidespeed.l10n

data class LanguageOption(
    val tag: String?,
    val name: String?,
    val selected: Boolean,
    val notComplete: Boolean,
)

private const val ENGLISH = "en"

fun languageOptions(
    shipped: List<String>,
    chosen: String?,
    nativeName: (String) -> String,
): List<LanguageOption> {
    val shippedSet = shipped.toSet()
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
            selected = chosen == ENGLISH,
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
                selected = tag == chosen,
                notComplete = false,
            )
    }

    if (chosen != null && chosen != ENGLISH && chosen !in shippedSet) {
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