package io.github.devjardins.sidespeed.ui.apps

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.devjardins.sidespeed.R
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
import io.github.devjardins.sidespeed.ui.text.UiText
import io.github.devjardins.sidespeed.ui.text.uiText
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

data class Pill(val label: UiText, val tone: PillTone)

data class BadgePill(@StringRes val label: Int, val tone: PillTone)

/** The status pill for a row: the badge when known, "Checking…" while loading, otherwise none. */
fun pillFor(statusLoading: Boolean, badge: StatusBadge?): BadgePill? = when (badge) {
    StatusBadge.SPEED_OK -> BadgePill(R.string.apps_pill_speed_ok, PillTone.OK)
    StatusBadge.SPEED_PROFILE -> BadgePill(R.string.apps_pill_speed_profile, PillTone.WARN)
    StatusBadge.NEEDS_RECOMPILE -> BadgePill(R.string.apps_pill_not_compiled, PillTone.WARN)
    StatusBadge.UNKNOWN, null -> if (statusLoading) BadgePill(R.string.apps_pill_checking, PillTone.NEUTRAL) else null
}

/** The row pill for a maintained app, or null when the app is not maintained. */
fun maintenancePill(pkg: String, state: MaintenanceState): Pill? = when {
    pkg !in state.maintained -> null
    else -> when (state.phases[pkg]) {
        JobPhase.RUNNING -> Pill(UiText.Res(R.string.apps_pill_optimizing), PillTone.NEUTRAL)
        JobPhase.WAITING -> Pill(UiText.Res(R.string.apps_pill_waiting), PillTone.NEUTRAL)
        JobPhase.RETRY -> Pill(UiText.Res(R.string.apps_pill_retry), PillTone.WARN)
        null -> Pill(UiText.Res(R.string.apps_pill_optimized), PillTone.OK)
    }
}

/** The Apps header pill for SideSpeed itself, or null on debuggable builds (ART won't compile those). */
fun selfOptimizedPill(record: SelfCompile?, versionCode: Long, lastUpdateTime: Long, debuggable: Boolean): Pill? {
    if (debuggable) return null
    if (record != null && record.versionCode == versionCode && record.lastUpdateTime == lastUpdateTime) {
        return Pill(UiText.Res(R.string.home_self_optimized), PillTone.OK)
    }
    return Pill(UiText.Res(R.string.home_self_optimizes_on_leave), PillTone.NEUTRAL)
}

/** The "Maintenance" line under the Apps header, or null when there is nothing to say. */
fun maintenanceLine(state: MaintenanceState): UiText? = when {
    state.helperDown && state.queueLength > 0 -> UiText.Res(R.string.apps_maintenance_waiting_helper)
    state.maintained.isEmpty() && state.queueLength == 0 -> null
    state.queueLength > 0 -> UiText.Plural(R.plurals.apps_maintenance_waiting, state.queueLength, listOf(state.queueLength))
    else -> UiText.Res(R.string.apps_maintenance_up_to_date)
}

/**
 * The second header line: the "This TV: …" caption and the maintenance line joined with " · ".
 * Shows whichever exists, or null when neither does.
 */
fun headerCaption(tvAddress: String?, maintenance: UiText?): UiText? {
    val tv = tvAddress?.takeIf { it.isNotBlank() }
    val line = if (maintenance is UiText.Raw && maintenance.text.isBlank()) null else maintenance
    return when {
        tv != null && line != null -> uiText(R.string.home_header_caption, UiText.Raw(tv), line)
        tv != null -> UiText.Raw(tv)
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

    fun pill(pkg: String): BadgePill? = _state.value.let { pillFor(it.statusLoading, it.statuses[pkg]) }

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

class AutoOptimizeOption(val title: UiText, val description: UiText)

/** Plain-language label and explanation for each auto-optimize mode. */
fun autoOptimizeOption(mode: AutoOptimizeMode): AutoOptimizeOption = when (mode) {
    AutoOptimizeMode.ALL -> AutoOptimizeOption(
        UiText.Res(R.string.apps_mode_all_title),
        UiText.Res(R.string.apps_mode_all_desc),
    )
    AutoOptimizeMode.SIDELOADED -> AutoOptimizeOption(
        UiText.Res(R.string.apps_mode_sideloaded_title),
        UiText.Res(R.string.apps_mode_sideloaded_desc),
    )
    AutoOptimizeMode.SELECTED -> AutoOptimizeOption(
        UiText.Res(R.string.apps_mode_selected_title),
        UiText.Res(R.string.apps_mode_selected_desc),
    )
}
