package io.github.devjardins.sidespeed.helper

/** Shared Android package name validation and known app-store installer package names. */
object PackageNames {
    const val MAX_LENGTH = 255
    private val PATTERN = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")

    val STORE_INSTALLERS: Set<String> = setOf("com.android.vending", "com.amazon.venezia")

    /** True when [raw] is a valid untrimmed Android package name of at most [MAX_LENGTH] characters. */
    fun isValid(raw: String): Boolean = raw.length in 1..MAX_LENGTH && PATTERN.matches(raw)
}
