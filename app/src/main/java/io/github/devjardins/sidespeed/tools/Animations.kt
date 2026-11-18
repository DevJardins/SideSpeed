package io.github.devjardins.sidespeed.tools

import kotlinx.coroutines.flow.first

enum class AnimationLevel(val value: String) {
    NORMAL("1"),
    FASTER("0.5"),
    OFF("0"),
}

/**
 * Reads and writes the three global animation scales through the helper, keeping the user's
 * original values in [ToolsStore] so [restore] can put them back exactly (even non-standard
 * ones like `1.5`; an unset key restores to `1`).
 */
class Animations(
    private val commands: ToolsCommands,
    private val store: ToolsStore,
) {
    /** Current value of each scale; unreadable scales (helper down, failure) read as null. */
    suspend fun current(): Map<AnimationKey, String?> =
        AnimationKey.entries.associateWith { key ->
            when (val result = commands.getAnimationScale(key)) {
                is ToolResult.Ok -> result.value
                else -> null
            }
        }

    /**
     * Saves the current scales once (a later apply never overwrites them), then sets all three
     * to [level]. The first failing write is returned; nothing is saved when the originals
     * can't even be read.
     */
    suspend fun apply(level: AnimationLevel): ToolResult<Unit> {
        if (store.animationOriginals.first() == null) {
            val originals = AnimationKey.entries.associateWith { key ->
                when (val result = commands.getAnimationScale(key)) {
                    is ToolResult.Ok -> result.value
                    is ToolResult.HelperDown -> return ToolResult.HelperDown
                    is ToolResult.HelperOutdated -> return ToolResult.HelperOutdated
                    is ToolResult.Failed -> return result
                }
            }
            store.setAnimationOriginals(
                AnimationOriginals(
                    window = originals[AnimationKey.WINDOW],
                    transition = originals[AnimationKey.TRANSITION],
                    animator = originals[AnimationKey.ANIMATOR],
                ),
            )
        }
        for (key in AnimationKey.entries) {
            when (val result = commands.putAnimationScale(key, level.value)) {
                is ToolResult.Ok -> {}
                is ToolResult.HelperDown -> return ToolResult.HelperDown
                is ToolResult.HelperOutdated -> return ToolResult.HelperOutdated
                is ToolResult.Failed -> return result
            }
        }
        return ToolResult.Ok(Unit)
    }

    /**
     * Writes the saved originals back (unset keys become `1`) and clears them. Nothing saved
     * means nothing to do. A failed write keeps the saved originals so restore can be retried.
     */
    suspend fun restore(): ToolResult<Unit> {
        val originals = store.animationOriginals.first() ?: return ToolResult.Ok(Unit)
        val values = mapOf(
            AnimationKey.WINDOW to (originals.window ?: "1"),
            AnimationKey.TRANSITION to (originals.transition ?: "1"),
            AnimationKey.ANIMATOR to (originals.animator ?: "1"),
        )
        for ((key, value) in values) {
            when (val result = commands.putAnimationScale(key, value)) {
                is ToolResult.Ok -> {}
                is ToolResult.HelperDown -> return ToolResult.HelperDown
                is ToolResult.HelperOutdated -> return ToolResult.HelperOutdated
                is ToolResult.Failed -> return result
            }
        }
        store.setAnimationOriginals(null)
        return ToolResult.Ok(Unit)
    }
}
