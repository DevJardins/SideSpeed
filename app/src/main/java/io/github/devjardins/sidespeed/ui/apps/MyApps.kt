package io.github.devjardins.sidespeed.ui.apps

import androidx.annotation.StringRes
import io.github.devjardins.sidespeed.R
import io.github.devjardins.sidespeed.apps.AppEntry
import io.github.devjardins.sidespeed.compile.CompileMode
import io.github.devjardins.sidespeed.data.LastCompile
import io.github.devjardins.sidespeed.maintenance.AutoOptimizeMode
import io.github.devjardins.sidespeed.maintenance.MaintenanceState
import io.github.devjardins.sidespeed.ui.text.UiText
import io.github.devjardins.sidespeed.ui.text.uiText

enum class AppsFilter(@StringRes val label: Int) {
    ALL(R.string.apps_filter_all),
    SIDELOADED(R.string.apps_filter_sideloaded),
}

/** Restores the saved filter; an unknown or missing name (e.g. 0.6's "HISTORY") falls back to ALL. */
fun filterFromSaved(name: String?): AppsFilter = AppsFilter.entries.firstOrNull { it.name == name } ?: AppsFilter.ALL

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

fun autoOptimizeButtonLabel(mode: AutoOptimizeMode): UiText =
    uiText(R.string.apps_auto_optimize_button, autoOptimizeOption(mode).title)

fun lockedMessage(mode: AutoOptimizeMode): UiText =
    uiText(R.string.apps_locked_message, autoOptimizeOption(mode).title)

/** The Compile screen mode: the app's last-used mode, or speed when it has never compiled. */
fun initialCompileMode(last: LastCompile?): CompileMode = last?.mode ?: CompileMode.SPEED

/** The My Apps tile status on Home: update count when any, else total then sideloaded counts, or a loading line. */
fun myAppsStatus(apps: List<AppEntry>, loading: Boolean, updates: Int = 0): UiText {
    if (loading) return UiText.Res(R.string.apps_loading)
    val appsPart = UiText.Plural(R.plurals.home_apps_count, apps.size, listOf(apps.size))
    val detailPart = if (updates > 0) {
        UiText.Plural(R.plurals.home_updates_count, updates, listOf(updates))
    } else {
        val sideloaded = apps.count { it.isSideloaded }
        UiText.Plural(R.plurals.home_sideloaded_count, sideloaded, listOf(sideloaded))
    }
    return uiText(R.string.home_status_my_apps, appsPart, detailPart)
}

/** The Auto-optimize tile status on Home: the mode plus what maintenance still has to do. */
fun autoOptimizeStatus(mode: AutoOptimizeMode?, state: MaintenanceState): UiText {
    if (mode == null) return UiText.Raw("")
    val detail = when {
        state.helperDown && state.queueLength > 0 -> UiText.Res(R.string.home_auto_waiting_helper)
        state.queueLength > 0 -> UiText.Plural(R.plurals.home_auto_waiting, state.queueLength, listOf(state.queueLength))
        else -> UiText.Res(R.string.home_auto_up_to_date)
    }
    return uiText(R.string.home_status_auto_optimize, autoOptimizeOption(mode).title, detail)
}

val TOOLS_STATUS: UiText = UiText.Res(R.string.home_status_tools)

fun aboutStatus(versionName: String): UiText = uiText(R.string.home_status_about, UiText.Ltr(versionName))
