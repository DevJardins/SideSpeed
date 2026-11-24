package io.github.devjardins.sidespeed.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import io.github.devjardins.sidespeed.R
import io.github.devjardins.sidespeed.data.SelfCompile
import io.github.devjardins.sidespeed.maintenance.AutoOptimizeMode
import io.github.devjardins.sidespeed.maintenance.MaintenanceState
import io.github.devjardins.sidespeed.ui.apps.AppsViewModel
import io.github.devjardins.sidespeed.ui.apps.TOOLS_STATUS
import io.github.devjardins.sidespeed.ui.apps.StatusPill
import io.github.devjardins.sidespeed.ui.apps.aboutStatus
import io.github.devjardins.sidespeed.ui.apps.autoOptimizeStatus
import io.github.devjardins.sidespeed.ui.apps.headerCaption
import io.github.devjardins.sidespeed.ui.apps.maintenanceLine
import io.github.devjardins.sidespeed.ui.apps.myAppsStatus
import io.github.devjardins.sidespeed.ui.apps.selfOptimizedPill
import io.github.devjardins.sidespeed.ui.text.UiText
import io.github.devjardins.sidespeed.ui.text.resolve
import io.github.devjardins.sidespeed.ui.theme.SsColors
import io.github.devjardins.sidespeed.ui.theme.SsPill
import io.github.devjardins.sidespeed.ui.theme.SsRow
import io.github.devjardins.sidespeed.ui.theme.SsScreen
import io.github.devjardins.sidespeed.ui.theme.SsType
import io.github.devjardins.sidespeed.updates.UpdateEntry
import io.github.devjardins.sidespeed.updates.myAppsUpdatesCount
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow

/** The Home tile that keeps focus when coming back from its screen. */
enum class HomeTile { MY_APPS, AUTO_OPTIMIZE, TOOLS, ABOUT }

/** Restores the saved tile; an unknown or missing name falls back to MY_APPS. */
fun homeTileFromSaved(name: String?): HomeTile =
    HomeTile.entries.firstOrNull { it.name == name } ?: HomeTile.MY_APPS

/**
 * The Home screen: the SideSpeed header and four big buttons (My Apps,
 * Auto-optimize, Tools, About), each with a one-line status. [focusedTile]
 * takes focus: My Apps on first launch, otherwise the tile just come from.
 * Back leaves the app as before.
 */
@Composable
fun HomeScreen(
    vm: AppsViewModel,
    maintenance: StateFlow<MaintenanceState>,
    autoOptimizeMode: Flow<AutoOptimizeMode>,
    onOpenMyApps: () -> Unit,
    onOpenAutoOptimize: () -> Unit,
    onOpenTools: () -> Unit,
    onOpenAbout: () -> Unit,
    banner: @Composable () -> Unit = {},
    tvAddress: UiText? = null,
    selfCompile: Flow<SelfCompile?>,
    selfVersionCode: Long,
    selfLastUpdateTime: Long,
    selfDebuggable: Boolean,
    versionName: String,
    focusedTile: HomeTile = HomeTile.MY_APPS,
    appUpdates: Flow<Map<String, UpdateEntry>> = emptyFlow(),
    onVisible: () -> Unit = {},
) {
    val state by vm.state.collectAsState()
    val maintenanceState by maintenance.collectAsState()
    val optimizeMode: AutoOptimizeMode? by autoOptimizeMode.collectAsState(initial = null)
    val selfRecord by selfCompile.collectAsState(initial = null)
    val catalog by appUpdates.collectAsState(initial = emptyMap())
    val updateCount = remember(state.apps, catalog) { myAppsUpdatesCount(state.apps, catalog) }
    // The My Apps tile counts the apps, so keep the list filled while Home shows.
    LaunchedEffect(Unit) {
        vm.load()
        onVisible()
    }
    // Give the D-pad a starting point: the tile just come from, or My Apps first.
    val myAppsFocus = remember { FocusRequester() }
    val autoOptimizeFocus = remember { FocusRequester() }
    val toolsFocus = remember { FocusRequester() }
    val aboutFocus = remember { FocusRequester() }
    LaunchedEffect(focusedTile) {
        // The screen just come from releases D-pad focus a frame after it leaves;
        // wait that out so this request wins instead of being reset to My Apps.
        withFrameNanos { }
        withFrameNanos { }
        runCatching {
            when (focusedTile) {
                HomeTile.MY_APPS -> myAppsFocus
                HomeTile.AUTO_OPTIMIZE -> autoOptimizeFocus
                HomeTile.TOOLS -> toolsFocus
                HomeTile.ABOUT -> aboutFocus
            }.requestFocus()
        }
    }
    SsScreen {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.app_name),
                    style = SsType.Headline,
                    color = SsColors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.weight(1f))
                selfOptimizedPill(selfRecord, selfVersionCode, selfLastUpdateTime, selfDebuggable)?.let {
                    StatusPill(it)
                    Spacer(Modifier.width(8.dp))
                }
                SsPill(stringResource(R.string.home_helper_running), SsColors.OkPillBg, SsColors.OkPillText)
            }
            headerCaption(tvAddress, maintenanceLine(maintenanceState))?.let {
                Text(it.resolve(), style = SsType.Caption, color = SsColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            banner()
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                HomeTile(
                    stringResource(R.string.home_tile_my_apps),
                    myAppsStatus(state.apps, state.loading, updateCount).resolve(),
                    onClick = onOpenMyApps,
                    modifier = Modifier.weight(1f).focusRequester(myAppsFocus),
                )
                HomeTile(
                    stringResource(R.string.home_tile_auto_optimize),
                    autoOptimizeStatus(optimizeMode, maintenanceState).resolve(),
                    onClick = onOpenAutoOptimize,
                    modifier = Modifier.weight(1f).focusRequester(autoOptimizeFocus),
                )
                HomeTile(
                    stringResource(R.string.home_tile_tools),
                    TOOLS_STATUS.resolve(),
                    onClick = onOpenTools,
                    modifier = Modifier.weight(1f).focusRequester(toolsFocus),
                )
                HomeTile(
                    stringResource(R.string.home_tile_about),
                    aboutStatus(versionName).resolve(),
                    onClick = onOpenAbout,
                    modifier = Modifier.weight(1f).focusRequester(aboutFocus),
                )
            }
        }
    }
}

@Composable
private fun HomeTile(title: String, status: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    SsRow(onClick = onClick, modifier = modifier.heightIn(min = 120.dp)) {
        Column(
            Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, style = SsType.Title, color = SsColors.TextPrimary)
            Text(
                status,
                style = SsType.Caption,
                color = SsColors.TextSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
