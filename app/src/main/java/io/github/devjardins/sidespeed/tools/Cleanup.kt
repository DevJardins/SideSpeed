package io.github.devjardins.sidespeed.tools

import kotlinx.coroutines.flow.first

/**
 * Free-up-space actions over [ToolsCommands.trimCaches]. Every run is persisted to
 * [ToolsStore.setLastCleanup] so the Tools screen can show the last line.
 */
class Cleanup(
    private val commands: ToolsCommands,
    private val deviceInfo: DeviceInfo,
    private val store: ToolsStore,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** Clears all caches the helper can and records freed = free-after − free-before (clamped ≥ 0). */
    suspend fun cleanNow(): CleanupRecord {
        val before = deviceInfo.storage().freeBytes
        val record = when (val result = commands.trimCaches(CLEAN_NOW_TARGET_BYTES)) {
            is ToolResult.Ok -> {
                val after = deviceInfo.storage().freeBytes
                CleanupRecord(atMs = clock(), freedBytes = (after - before).coerceAtLeast(0), skipped = null)
            }
            is ToolResult.HelperDown -> CleanupRecord(atMs = clock(), freedBytes = null, skipped = "Helper not running")
            is ToolResult.HelperOutdated -> CleanupRecord(atMs = clock(), freedBytes = null, skipped = "Restart the helper")
            is ToolResult.Failed -> CleanupRecord(atMs = clock(), freedBytes = null, skipped = result.message)
        }
        store.setLastCleanup(record)
        return record
    }

    /**
     * One automatic-cleanup pass for the 6 h loop: null (no call, no record) when the setting is
     * off or free space is already at/above the threshold, otherwise trims to the threshold.
     */
    suspend fun maybeAuto(): CleanupRecord? {
        val thresholdBytes = store.cleanupThreshold.first().bytes ?: return null
        val before = deviceInfo.storage().freeBytes
        if (before >= thresholdBytes) return null
        val record = when (val result = commands.trimCaches(thresholdBytes)) {
            is ToolResult.Ok -> {
                val after = deviceInfo.storage().freeBytes
                CleanupRecord(atMs = clock(), freedBytes = (after - before).coerceAtLeast(0), skipped = null)
            }
            is ToolResult.HelperDown -> CleanupRecord(atMs = clock(), freedBytes = null, skipped = "Helper not running")
            is ToolResult.HelperOutdated -> CleanupRecord(atMs = clock(), freedBytes = null, skipped = "Restart the helper")
            is ToolResult.Failed -> CleanupRecord(atMs = clock(), freedBytes = null, skipped = result.message)
        }
        store.setLastCleanup(record)
        return record
    }

    companion object {
        /** Very large target: clears all caches the helper can. */
        const val CLEAN_NOW_TARGET_BYTES = 999_999_999_999_999L
    }
}
