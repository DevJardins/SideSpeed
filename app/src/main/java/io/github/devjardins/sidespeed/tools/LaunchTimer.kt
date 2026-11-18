package io.github.devjardins.sidespeed.tools

/**
 * Times cold launches (`am start -W -S`) and reports the median. Every run —
 * success or failure — ends with [ToolsCommands.bringBack] so SideSpeed
 * returns to the front; a mid-command connection loss surfaces as
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
            when (val result = commands.launchTimeMs(pkg, cls)) {
                is ToolResult.Ok -> samples += result.value
                is ToolResult.HelperDown -> {
                    commands.bringBack()
                    return ToolResult.HelperDown
                }
                is ToolResult.HelperOutdated -> {
                    commands.bringBack()
                    return ToolResult.HelperOutdated
                }
                is ToolResult.Failed -> {
                    commands.bringBack()
                    return result
                }
            }
            commands.bringBack()
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
