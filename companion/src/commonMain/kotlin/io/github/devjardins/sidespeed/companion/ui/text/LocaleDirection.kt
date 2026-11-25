package io.github.devjardins.sidespeed.companion.ui.text

import androidx.compose.ui.unit.LayoutDirection

private val RTL_SCRIPTS = setOf("Arab", "Hebr", "Syrc", "Thaa", "Nkoo", "Adlm", "Mand", "Samr", "Mend", "Rohg")
private val RTL_LANGUAGES = setOf(
    "ar", "he", "iw", "fa", "ur", "yi", "ji", "ps", "sd", "ug", "dv", "ckb",
    "syr", "nqo", "ks", "arc", "azb", "glk", "mzn", "pnb", "skr", "prs", "lrc", "bqi",
)

fun isRtlLanguageOrScript(language: String, script: String, componentOrientationLtr: Boolean = true): Boolean {
    if (script.isNotEmpty()) return script in RTL_SCRIPTS
    return !componentOrientationLtr || language.lowercase() in RTL_LANGUAGES
}

expect fun isDefaultLocaleRtl(): Boolean

/** Layout direction for an explicit tag, so roots can follow the language actually shown. */
expect fun layoutDirectionForTag(tag: String): LayoutDirection
