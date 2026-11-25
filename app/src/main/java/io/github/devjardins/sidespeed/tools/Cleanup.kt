package io.github.devjardins.sidespeed.tools

import io.github.devjardins.sidespeed.R
import io.github.devjardins.sidespeed.ui.text.UiText
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    private val _running = MutableStateFlow(false)

    /** True while a cleanup run is in flight. */
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private suspend fun <T> tracked(block: suspend () -> T): T {
        _running.value = true
        try {
            return block()
        } finally {
            _running.value = false
        }
    }

    /** Clears all caches the helper can and records freed = free-after − free-before (clamped ≥ 0). */
    suspend fun cleanNow(): CleanupRecord = tracked {
        val before = deviceInfo.storage().freeBytes
        val record = recordFor(before, commands.trimCaches(CLEAN_NOW_TARGET_BYTES))
        store.setLastCleanup(record)
        record
    }

    /**
     * One automatic-cleanup pass for the 6 h loop: null (no call, no record) when the setting is
     * off or free space is already at/above the threshold, otherwise trims to the threshold.
     */
    suspend fun maybeAuto(): CleanupRecord? {
        val thresholdBytes = store.cleanupThreshold.first().bytes ?: return null
        val before = deviceInfo.storage().freeBytes
        if (before >= thresholdBytes) return null
        return tracked {
            val record = recordFor(before, commands.trimCaches(thresholdBytes))
            store.setLastCleanup(record)
            record
        }
    }

    private fun recordFor(before: Long, result: ToolResult<Unit>): CleanupRecord = when (result) {
        is ToolResult.Ok -> {
            val after = deviceInfo.storage().freeBytes
            CleanupRecord(atMs = clock(), freedBytes = (after - before).coerceAtLeast(0), skipped = null)
        }
        is ToolResult.HelperDown -> CleanupRecord(atMs = clock(), freedBytes = null, skipped = SkipReason.HELPER_DOWN.name)
        is ToolResult.HelperOutdated -> CleanupRecord(atMs = clock(), freedBytes = null, skipped = SkipReason.HELPER_OUTDATED.name)
        is ToolResult.Refused -> CleanupRecord(atMs = clock(), freedBytes = null, skipped = SkipReason.REFUSED.name)
        is ToolResult.Failed -> CleanupRecord(atMs = clock(), freedBytes = null, skipped = result.message)
    }

    companion object {
        /** Very large target: clears all caches the helper can. */
        const val CLEAN_NOW_TARGET_BYTES = 999_999_999_999_999L
    }
}

/** Short choice label, e.g. "1 GB": shared by the SpaceCard row and the service notification. */
internal fun CleanupThreshold.shortLabel(): UiText = UiText.Res(
    when (this) {
        CleanupThreshold.OFF -> R.string.tools_threshold_off
        CleanupThreshold.MB_500 -> R.string.tools_threshold_500_mb
        CleanupThreshold.GB_1 -> R.string.tools_threshold_1_gb
        CleanupThreshold.GB_2 -> R.string.tools_threshold_2_gb
    },
)
