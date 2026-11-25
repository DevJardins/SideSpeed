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

/**
 * A validated fully-qualified activity class name (Java binary name), e.g.
 * `com.foo.Main` or `com.foo.Main$Launcher` for a nested launcher activity.
 * Grammar: dot-separated segments of `[A-Za-z_][A-Za-z0-9_]*`, where each
 * segment may hold `$`-separated parts (`Outer$Inner`), at least one dot, no
 * leading/trailing `.` or `$`, no `..`, no `$$`, max 255 chars. Matching is
 * exact (no trimming): surrounding whitespace is rejected.
 *
 * Digits-only `$` parts (`Outer$1`, anonymous classes) are rejected: they are
 * never launchable activities. `$` is safe here because the helper passes the
 * class token as a fixed argv element (never a shell), and this is the only
 * token in any helper command that may contain it.
 */
@JvmInline
value class ActivityClassName private constructor(val value: String) {
    companion object {
        const val MAX_LENGTH = 255
        private const val IDENT = "[A-Za-z_][A-Za-z0-9_]*"
        private val PATTERN = Regex("^" + IDENT + "([$]" + IDENT + ")*(\\." + IDENT + "([$]" + IDENT + ")*)+$")

        fun parse(raw: String): ActivityClassName? =
            raw.takeIf { it.length in 1..MAX_LENGTH && PATTERN.matches(it) }?.let(::ActivityClassName)
    }
}

/** Typed outcome of a compile run, so UI and maintenance classify without matching English strings. */
sealed interface CompileOutcome {
    data object Success : CompileOutcome
    data object Failure : CompileOutcome
    data object ConnectionLost : CompileOutcome
    data class Error(val detail: String? = null) : CompileOutcome
}

sealed interface CompileEvent {
    data class Attempt(val command: String) : CompileEvent
    data class Output(val line: String) : CompileEvent
    data class Finished(
        val success: Boolean,
        val usedFallback: Boolean,
        val outcome: CompileOutcome = if (success) CompileOutcome.Success else CompileOutcome.Failure,
    ) : CompileEvent
}
