package io.github.devjardins.sidespeed.tools

import io.github.devjardins.sidespeed.adb.AdbConnectionLost
import io.github.devjardins.sidespeed.adb.CommandRunner
import io.github.devjardins.sidespeed.adb.ShellEvent
import io.github.devjardins.sidespeed.helper.HelperProtocol

sealed interface ToolResult<out T> {
    data class Ok<T>(val value: T) : ToolResult<T>
    data object HelperDown : ToolResult<Nothing>
    data object HelperOutdated : ToolResult<Nothing>
    data class Failed(val message: String) : ToolResult<Nothing>
}

enum class AnimationKey(val key: String) {
    WINDOW("window_animation_scale"),
    TRANSITION("transition_animation_scale"),
    ANIMATOR("animator_duration_scale"),
}

/**
 * Typed wrapper over the shell-uid helper for the Tools screen. Every call checks [helperUp] first
 * (helper not running → [ToolResult.HelperDown]) and maps exit 126 (`EXIT_REFUSED`, the stale-helper
 * refusal) to [ToolResult.HelperOutdated] so callers show "Restart the helper to use this".
 */
class ToolsCommands(
    private val runner: CommandRunner,
    private val helperUp: suspend () -> Boolean,
) {
    suspend fun trimCaches(targetFreeBytes: Long): ToolResult<Unit> =
        run("pm trim-caches $targetFreeBytes").map { }

    /** A `settings get` answer of "null" (unset key) maps to [ToolResult.Ok] with null. */
    suspend fun getAnimationScale(key: AnimationKey): ToolResult<String?> =
        run("settings get global ${key.key}").map { lines ->
            lines.firstOrNull()?.trim().takeUnless { it.isNullOrEmpty() || it == "null" }
        }

    suspend fun putAnimationScale(key: AnimationKey, value: String): ToolResult<Unit> =
        run("settings put global ${key.key} $value").map { }

    /** Resets the app's compiled code (`pm compile -m verify -f`), before a launch-time measurement. */
    suspend fun resetCompiled(pkg: String): ToolResult<Unit> =
        run("pm compile -m verify -f $pkg").map { }

    /** Parses `TotalTime: N` from `am start -W`; `Error:` lines or no TotalTime → [ToolResult.Failed]. */
    suspend fun launchTimeMs(pkg: String, cls: String): ToolResult<Long> =
        when (val result = run("launch-time $pkg $cls")) {
            is ToolResult.Ok -> {
                result.value.firstOrNull { it.startsWith("Error") }?.let { return ToolResult.Failed(it) }
                val ms = result.value.firstNotNullOfOrNull {
                    TOTAL_TIME.find(it)?.groupValues?.get(1)?.toLongOrNull()
                }
                if (ms == null) ToolResult.Failed("no TotalTime in output") else ToolResult.Ok(ms)
            }
            is ToolResult.HelperDown -> ToolResult.HelperDown
            is ToolResult.HelperOutdated -> ToolResult.HelperOutdated
            is ToolResult.Failed -> result
        }

    suspend fun bringBack(): ToolResult<Unit> = run("bring-back").map { }

    private fun <T, R> ToolResult<T>.map(f: (T) -> R): ToolResult<R> = when (this) {
        is ToolResult.Ok -> ToolResult.Ok(f(value))
        is ToolResult.HelperDown -> this
        is ToolResult.HelperOutdated -> this
        is ToolResult.Failed -> this
    }

    private suspend fun run(command: String): ToolResult<List<String>> {
        if (!helperUp()) return ToolResult.HelperDown
        val lines = mutableListOf<String>()
        var exit: Int? = null
        try {
            runner.shell(command).collect { event ->
                when (event) {
                    is ShellEvent.Line -> lines += event.text
                    is ShellEvent.Exit -> exit = event.code
                }
            }
        } catch (_: AdbConnectionLost) {
            return ToolResult.HelperDown
        }
        return when (exit) {
            HelperProtocol.EXIT_REFUSED -> ToolResult.HelperOutdated
            0 -> ToolResult.Ok(lines)
            else -> ToolResult.Failed(lines.lastOrNull()?.trim().orEmpty().ifEmpty { "exit $exit" })
        }
    }

    private companion object {
        val TOTAL_TIME = Regex("TotalTime: (\\d+)")
    }
}
