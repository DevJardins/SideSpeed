package io.github.devjardins.sidespeed.tools

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Times cold launches (`am start -W -S`) and reports the median. Every run —
 * success, failure, or cancellation — runs best-effort [ToolsCommands.forceStop]
 * when `launch-time` may have started the app and ends with [ToolsCommands.bringBack]
 * so SideSpeed returns to the front; a mid-command connection loss surfaces as
 * [ToolResult.HelperDown] via [ToolsCommands].
 */
class LaunchTimer(
    private val commands: ToolsCommands,
    private val runs: Int = 3,
) {
    /** Median launch time over [runs] cold starts, or the first failure. */
    suspend fun medianMs(pkg: String, cls: String): ToolResult<Long> {
        val samples = mutableListOf<Long>()
        repeat(runs) {
            var result: ToolResult<Long>? = null
            val r = try {
                commands.launchTimeMs(pkg, cls).also { result = it }
            } finally {
                withContext(NonCancellable) {
                    try {
                        if (result !is ToolResult.Refused && result !is ToolResult.HelperOutdated) {
                            commands.forceStop(pkg)
                        }
                    } finally {
                        commands.bringBack()
                    }
                }
            }
            when (r) {
                is ToolResult.Ok -> samples += r.value
                is ToolResult.HelperDown -> return ToolResult.HelperDown
                is ToolResult.HelperOutdated -> return ToolResult.HelperOutdated
                is ToolResult.Refused -> return ToolResult.Refused
                is ToolResult.Failed -> return r
            }
        }
        samples.sort()
        return ToolResult.Ok(samples[samples.size / 2])
    }
}

/**
 * True when a "before" measurement is still needed: no stored result, or the
 * installed app (versionCode + lastUpdateTime) is not the one that was timed.
 */
fun needsBeforeMeasure(stored: LaunchResult?, versionCode: Long, lastUpdateTime: Long): Boolean =
    stored == null || stored.versionCode != versionCode || stored.lastUpdateTime != lastUpdateTime
