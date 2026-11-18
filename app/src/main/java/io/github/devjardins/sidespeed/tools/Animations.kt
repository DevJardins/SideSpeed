package io.github.devjardins.sidespeed.tools

import kotlinx.coroutines.flow.first

enum class AnimationLevel(val value: String) {
    NORMAL("1"),
    FASTER("0.5"),
    OFF("0"),
}

/**
 * Matches raw scale readings to a level by parsed number, so firmware spellings like `1.0`
 * or `0.50` still select Normal/Faster. Any unreadable (`null`), unparsable, or mixed set
 * matches nothing.
 */
fun matchLevel(values: Collection<String?>): AnimationLevel? {
    if (values.isEmpty()) return null
    val numbers = values.map { it?.toDoubleOrNull() ?: return null }
    return AnimationLevel.entries.firstOrNull { level ->
        val want = level.value.toDoubleOrNull() ?: return@firstOrNull false
        numbers.all { it == want }
    }
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
     * Sets all three scales to [level]. The current scales are snapshotted first; the snapshot
     * is saved as the originals only after the first write lands, so a failed apply never
     * claims an original it didn't change. If a later write fails, the keys already written
     * are rolled back to the snapshot (best effort): a fully restored device keeps no
     * originals, a partially stuck one keeps the snapshot so restore can finish the job.
     */
    suspend fun apply(level: AnimationLevel): ToolResult<Unit> {
        val snapshot = when (val read = readAll()) {
            is ToolResult.Ok -> read.value
            is ToolResult.HelperDown -> return ToolResult.HelperDown
            is ToolResult.HelperOutdated -> return ToolResult.HelperOutdated
            is ToolResult.Failed -> return read
        }
        val hadOriginals = store.animationOriginals.first() != null
        val written = mutableListOf<AnimationKey>()
        for (key in AnimationKey.entries) {
            when (val result = commands.putAnimationScale(key, level.value)) {
                is ToolResult.Ok -> {
                    written += key
                    if (written.size == 1 && !hadOriginals) {
                        store.setAnimationOriginals(
                            AnimationOriginals(
                                window = snapshot[AnimationKey.WINDOW],
                                transition = snapshot[AnimationKey.TRANSITION],
                                animator = snapshot[AnimationKey.ANIMATOR],
                            ),
                        )
                    }
                }
                else -> {
                    val rolledBack = rollback(written, snapshot)
                    if (rolledBack && !hadOriginals) store.setAnimationOriginals(null)
                    return result
                }
            }
        }
        return ToolResult.Ok(Unit)
    }

    /**
     * Writes the saved originals back (unset keys become `1`) and clears them. Nothing saved
     * means nothing to do. A key that fails mid-restore is rolled back to the pre-restore
     * snapshot; the saved originals are kept so restore can be retried.
     */
    suspend fun restore(): ToolResult<Unit> {
        val originals = store.animationOriginals.first() ?: return ToolResult.Ok(Unit)
        val snapshot = when (val read = readAll()) {
            is ToolResult.Ok -> read.value
            is ToolResult.HelperDown -> return ToolResult.HelperDown
            is ToolResult.HelperOutdated -> return ToolResult.HelperOutdated
            is ToolResult.Failed -> return read
        }
        val values = mapOf(
            AnimationKey.WINDOW to (originals.window ?: "1"),
            AnimationKey.TRANSITION to (originals.transition ?: "1"),
            AnimationKey.ANIMATOR to (originals.animator ?: "1"),
        )
        val written = mutableListOf<AnimationKey>()
        for ((key, value) in values) {
            when (val result = commands.putAnimationScale(key, value)) {
                is ToolResult.Ok -> written += key
                else -> {
                    rollback(written, snapshot)
                    return result
                }
            }
        }
        store.setAnimationOriginals(null)
        return ToolResult.Ok(Unit)
    }

    /** Current value of every scale; the first read error instead. */
    private suspend fun readAll(): ToolResult<Map<AnimationKey, String?>> {
        val snapshot = mutableMapOf<AnimationKey, String?>()
        for (key in AnimationKey.entries) {
            when (val result = commands.getAnimationScale(key)) {
                is ToolResult.Ok -> snapshot[key] = result.value
                is ToolResult.HelperDown -> return ToolResult.HelperDown
                is ToolResult.HelperOutdated -> return ToolResult.HelperOutdated
                is ToolResult.Failed -> return result
            }
        }
        return ToolResult.Ok(snapshot)
    }

    /** Best-effort write-back of [written] keys to [snapshot]; true when every write landed. */
    private suspend fun rollback(written: List<AnimationKey>, snapshot: Map<AnimationKey, String?>): Boolean {
        var ok = true
        for (key in written) {
            // A key that was unset reads as null; restoring it means putting the default back.
            when (commands.putAnimationScale(key, snapshot[key] ?: "1")) {
                is ToolResult.Ok -> {}
                else -> ok = false
            }
        }
        return ok
    }
}
