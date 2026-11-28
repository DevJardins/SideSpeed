package sidespeed.l10n

/**
 * jlink `--include-locales` value for the desktop runtime: English plus every shipped
 * language tag from the language gate, comma-separated. The tags come from
 * `LanguageGate.evaluate(...)` (the same inputs `GenerateShippedLanguages` uses),
 * never from a hand-written list, so a language that ships also keeps its locale data.
 */
fun includeLocalesArg(shippedTags: List<String>): String =
    "--include-locales=" + (listOf("en") + shippedTags).joinToString(",")
