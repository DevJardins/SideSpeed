package io.github.devjardins.sidespeed.ui.apps

import io.github.devjardins.sidespeed.apps.AppEntry
import io.github.devjardins.sidespeed.compile.CompileMode
import io.github.devjardins.sidespeed.data.LastCompile
import io.github.devjardins.sidespeed.maintenance.AutoOptimizeMode

enum class AppsFilter(val label: String) { SIDELOADED("Sideloaded"), ALL("All") }

/** Restores the saved filter; an unknown or missing name (e.g. 0.6's "HISTORY") falls back to SIDELOADED. */
fun filterFromSaved(name: String?): AppsFilter = AppsFilter.entries.firstOrNull { it.name == name } ?: AppsFilter.SIDELOADED

enum class KeepBox { OFF, ON, LOCKED }

/**
 * The checkbox state for an app row: LOCKED when the mode already covers the app
 * (Sideloaded covers sideloaded apps, All covers every non-system app), otherwise
 * ON/OFF from the app's own ticked state. Locking never changes the ticked set.
 */
fun boxFor(app: AppEntry, mode: AutoOptimizeMode, ticked: Set<String>): KeepBox {
    val covered = when (mode) {
        AutoOptimizeMode.SIDELOADED -> app.isSideloaded
        AutoOptimizeMode.ALL -> !app.isSystem
        AutoOptimizeMode.SELECTED -> false
    }
    if (covered) return KeepBox.LOCKED
    return if (app.packageName in ticked) KeepBox.ON else KeepBox.OFF
}

fun autoOptimizeButtonLabel(mode: AutoOptimizeMode): String = "Auto-optimize: " + autoOptimizeOption(mode).title

fun lockedMessage(mode: AutoOptimizeMode): String =
    "Kept optimized by Auto-optimize (${autoOptimizeOption(mode).title}). Change it with the Auto-optimize button."

/** The Compile screen mode: the app's last-used mode, or speed when it has never compiled. */
fun initialCompileMode(last: LastCompile?): CompileMode = last?.mode ?: CompileMode.SPEED
