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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
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
import io.github.devjardins.sidespeed.ui.theme.SsColors
import io.github.devjardins.sidespeed.ui.theme.SsPill
import io.github.devjardins.sidespeed.ui.theme.SsRow
import io.github.devjardins.sidespeed.ui.theme.SsScreen
import io.github.devjardins.sidespeed.ui.theme.SsType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * The Home screen: the SideSpeed header and four big buttons (My Apps,
 * Auto-optimize, Tools, About), each with a one-line status. My Apps takes
 * the initial focus; Back leaves the app as before.
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
    tvAddress: String? = null,
    selfCompile: Flow<SelfCompile?>,
    selfVersionCode: Long,
    selfLastUpdateTime: Long,
    selfDebuggable: Boolean,
    versionName: String,
) {
    val state by vm.state.collectAsState()
    val maintenanceState by maintenance.collectAsState()
    val optimizeMode: AutoOptimizeMode? by autoOptimizeMode.collectAsState(initial = null)
    val selfRecord by selfCompile.collectAsState(initial = null)
    // The My Apps tile counts the apps, so keep the list filled while Home shows.
    LaunchedEffect(Unit) { vm.load() }
    // Give the D-pad a starting point: My Apps takes focus when the screen opens.
    val myAppsFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { myAppsFocus.requestFocus() } }
    SsScreen {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "SideSpeed",
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
                SsPill("● Helper running", SsColors.OkPillBg, SsColors.OkPillText)
            }
            headerCaption(tvAddress, maintenanceLine(maintenanceState))?.let {
                Text(it, style = SsType.Caption, color = SsColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            banner()
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                HomeTile(
                    "My Apps",
                    myAppsStatus(state.apps, state.loading),
                    onClick = onOpenMyApps,
                    modifier = Modifier.weight(1f).focusRequester(myAppsFocus),
                )
                HomeTile(
                    "Auto-optimize",
                    autoOptimizeStatus(optimizeMode, maintenanceState),
                    onClick = onOpenAutoOptimize,
                    modifier = Modifier.weight(1f),
                )
                HomeTile(
                    "Tools",
                    TOOLS_STATUS,
                    onClick = onOpenTools,
                    modifier = Modifier.weight(1f),
                )
                HomeTile(
                    "About",
                    aboutStatus(versionName),
                    onClick = onOpenAbout,
                    modifier = Modifier.weight(1f),
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
