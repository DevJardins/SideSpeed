package io.github.devjardins.sidespeed.companion.ui.text

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
