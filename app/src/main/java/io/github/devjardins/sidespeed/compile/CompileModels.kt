package io.github.devjardins.sidespeed.compile

enum class CompileMode(val arg: String) {
    SPEED("speed"),
    SPEED_PROFILE("speed-profile"),
}

/** A validated Android package name. Only this type may be interpolated into a shell command. */
@JvmInline
value class PackageName private constructor(val value: String) {
    companion object {
        private val PATTERN = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")

        fun parse(raw: String): PackageName? = raw.trim().takeIf { PATTERN.matches(it) }?.let(::PackageName)
    }
}

sealed interface CompileEvent {
    data class Attempt(val command: String) : CompileEvent
    data class Output(val line: String) : CompileEvent
    data class Finished(val success: Boolean, val usedFallback: Boolean, val message: String) : CompileEvent
}
