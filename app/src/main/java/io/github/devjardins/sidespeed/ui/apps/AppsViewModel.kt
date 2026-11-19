package io.github.devjardins.sidespeed.ui.apps

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.devjardins.sidespeed.apps.AppEntry
import io.github.devjardins.sidespeed.apps.AppSource
import io.github.devjardins.sidespeed.apps.sortForPicker
import io.github.devjardins.sidespeed.compile.PackageName
import io.github.devjardins.sidespeed.data.SelfCompile
import io.github.devjardins.sidespeed.maintenance.JobPhase
import io.github.devjardins.sidespeed.maintenance.AutoOptimizeMode
import io.github.devjardins.sidespeed.maintenance.MaintenanceState
import io.github.devjardins.sidespeed.status.BadgeSource
import io.github.devjardins.sidespeed.status.StatusBadge
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/**
 * [statuses]: compiler badges by package, from one status call. [statusLoading]: that call has not returned.
 * A failed call leaves [statuses] empty, so no pills are shown.
 */
data class AppsUiState(
    val apps: List<AppEntry> = emptyList(),
    val loading: Boolean = true,
    val statuses: Map<String, StatusBadge> = emptyMap(),
    val statusLoading: Boolean = false,
)

enum class PillTone { OK, WARN, NEUTRAL }

data class Pill(val label: String, val tone: PillTone)

/** The status pill for a row: the badge when known, "Checking…" while loading, otherwise none. */
fun pillFor(statusLoading: Boolean, badge: StatusBadge?): Pill? = when (badge) {
    StatusBadge.SPEED_OK -> Pill("speed ✓", PillTone.OK)
    StatusBadge.SPEED_PROFILE -> Pill("speed-profile", PillTone.WARN)
    StatusBadge.NEEDS_RECOMPILE -> Pill("Not compiled", PillTone.WARN)
    StatusBadge.UNKNOWN, null -> if (statusLoading) Pill("Checking…", PillTone.NEUTRAL) else null
}

/** The row pill for a maintained app, or null when the app is not maintained. */
fun maintenancePill(pkg: String, state: MaintenanceState): Pill? = when {
    pkg !in state.maintained -> null
    else -> when (state.phases[pkg]) {
        JobPhase.RUNNING -> Pill("Optimizing…", PillTone.NEUTRAL)
        JobPhase.WAITING -> Pill("Waiting", PillTone.NEUTRAL)
        JobPhase.RETRY -> Pill("Retry", PillTone.WARN)
        null -> Pill("Optimized", PillTone.OK)
    }
}

/** The Apps header pill for SideSpeed itself, or null on debuggable builds (ART won't compile those). */
fun selfOptimizedPill(record: SelfCompile?, versionCode: Long, lastUpdateTime: Long, debuggable: Boolean): Pill? {
    if (debuggable) return null
    if (record != null && record.versionCode == versionCode && record.lastUpdateTime == lastUpdateTime) {
        return Pill("SideSpeed optimized", PillTone.OK)
    }
    return Pill("SideSpeed optimizes itself when you leave", PillTone.NEUTRAL)
}

/** The "Maintenance" line under the Apps header, or null when there is nothing to say. */
fun maintenanceLine(state: MaintenanceState): String? = when {
    state.helperDown && state.queueLength > 0 -> "Waiting for the helper"
    state.maintained.isEmpty() && state.queueLength == 0 -> null
    state.queueLength > 0 -> "Maintenance: ${state.queueLength} waiting"
    else -> "Maintenance: up to date"
}

/**
 * The second header line: the "This TV: …" caption and the maintenance line joined with " · ".
 * Shows whichever exists, or null when neither does.
 */
fun headerCaption(tvAddress: String?, maintenance: String?): String? {
    val tv = tvAddress?.takeIf { it.isNotBlank() }
    val line = maintenance?.takeIf { it.isNotBlank() }
    return when {
        tv != null && line != null -> "$tv · $line"
        tv != null -> tv
        line != null -> line
        else -> null
    }
}

class AppsViewModel(
    private val source: AppSource,
    private val badgeSource: BadgeSource,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val _state = MutableStateFlow(AppsUiState())
    val state: StateFlow<AppsUiState> = _state.asStateFlow()
    private var loadJob: Job? = null

    /**
     * Loads the app list on the injected IO dispatcher, never on Main, then fills every row's status from
     * one `dumpsys package dexopt` call. Badges from an earlier load stay shown until the new call returns.
     */
    fun load() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch(ioDispatcher) {
            val apps = sortForPicker(source.list())
            _state.update { it.copy(apps = apps, loading = false, statusLoading = true) }
            val statuses = try {
                badgeSource.badges(apps.map { it.packageName })
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                emptyMap()
            }
            _state.update { it.copy(statuses = statuses, statusLoading = false) }
        }
    }

    fun pill(pkg: String): Pill? = _state.value.let { pillFor(it.statusLoading, it.statuses[pkg]) }

    /**
     * Resolves a package name to its installed app, or null when the name is not a
     * valid package name or the app is not installed. Used to restore a selected
     * app after process/config restore (the package may no longer be installed).
     */
    fun findApp(raw: String): AppEntry? {
        val pkg = PackageName.parse(raw) ?: return null
        return source.find(pkg.value)
    }
}

class AutoOptimizeOption(val title: String, val description: String)

/** Plain-language label and explanation for each auto-optimize mode. */
fun autoOptimizeOption(mode: AutoOptimizeMode): AutoOptimizeOption = when (mode) {
    AutoOptimizeMode.ALL -> AutoOptimizeOption(
        "All apps",
        "Every app you installed, from a store or not, is compiled to speed and kept there after updates. Preinstalled apps are left alone unless you pick them.",
    )
    AutoOptimizeMode.SIDELOADED -> AutoOptimizeOption(
        "Sideloaded only",
        "Only apps that did not come from a store are kept optimized. Apps you pick yourself are included too.",
    )
    AutoOptimizeMode.SELECTED -> AutoOptimizeOption(
        "Selected apps",
        "Only the apps you pick. Open an app and choose \u201cKeep this app optimized\u201d to add it.",
    )
}
