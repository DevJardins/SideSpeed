package io.github.devjardins.sidespeed.ui.tools

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.tv.material3.Text
import io.github.devjardins.sidespeed.R
import io.github.devjardins.sidespeed.maintenance.RebootRecoveryStore
import io.github.devjardins.sidespeed.tools.EnableResult
import io.github.devjardins.sidespeed.tools.RebootRecoveryController
import io.github.devjardins.sidespeed.tools.RecoveryNotice
import io.github.devjardins.sidespeed.ui.theme.SsButton
import io.github.devjardins.sidespeed.ui.theme.SsCard
import io.github.devjardins.sidespeed.ui.theme.SsColors
import io.github.devjardins.sidespeed.ui.theme.SsPill
import io.github.devjardins.sidespeed.ui.theme.SsScreen
import io.github.devjardins.sidespeed.ui.theme.SsSpinner
import io.github.devjardins.sidespeed.ui.theme.SsType
import kotlinx.coroutines.launch

/**
 * Tools card slot for "After a restart" on Google TV / Android TV 11+.
 * Hidden on Fire OS (Fire TV support waits for a follow-up PR).
 */
@Composable
fun RebootRecoverySlot(
    controller: RebootRecoveryController,
    store: RebootRecoveryStore,
    switchFocus: FocusRequester,
    pendingEnableAfterConfirm: Boolean,
    onConsumePendingEnable: () -> Unit,
    onRequestPairing: () -> Unit,
    onRequestConfirm: () -> Unit,
) {
    if (controller.isFireOs) return

    val enabled by store.enabled.collectAsState(initial = false)
    val pendingRevokes by store.pendingRevokes.collectAsState(initial = false)
    var canEnable by remember { mutableStateOf(controller.canEnable()) }
    var busy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<RecoveryNotice?>(null) }
    var pendingSecureRevoke by remember { mutableStateOf(controller.hasPendingSecureSettingsRevoke()) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(enabled, pendingRevokes, busy) {
        pendingSecureRevoke = controller.hasPendingSecureSettingsRevoke()
    }

    LaunchedEffect(enabled) {
        canEnable = controller.canEnable()
        if (enabled && !busy) {
            notice = controller.syncTrustedBssidsIfEnabled()
        }
    }

    LaunchedEffect(pendingEnableAfterConfirm) {
        if (!pendingEnableAfterConfirm) return@LaunchedEffect
        onConsumePendingEnable()
        busy = true
        scope.launch {
            try {
                val result = controller.enable()
                notice = when (result) {
                    EnableResult.Enabled -> null
                    EnableResult.NeedsPairing -> {
                        onRequestPairing()
                        null
                    }
                    EnableResult.NeedsLocationSetting -> RecoveryNotice.NEEDS_LOCATION_SETTING
                    EnableResult.NeedsAlwaysAllowPrompt -> RecoveryNotice.NEEDS_ALWAYS_ALLOW_PROMPT
                    EnableResult.NeedsAlwaysAllowToggle -> RecoveryNotice.NEEDS_ALWAYS_ALLOW_TOGGLE
                    EnableResult.NoWifiOrOldAndroid -> RecoveryNotice.NEEDS_WIFI_OR_ANDROID_11
                    EnableResult.HelperDown -> RecoveryNotice.HELPER_DOWN
                    EnableResult.GrantFailed -> RecoveryNotice.GRANT_FAILED
                }
            } finally {
                busy = false
            }
        }
    }

    // Re-check Wi-Fi, location, and Always-allow state whenever the user returns from TV Settings.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, enabled, busy) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                canEnable = controller.canEnable()
                pendingSecureRevoke = controller.hasPendingSecureSettingsRevoke()
                if (enabled && !busy) {
                    scope.launch { notice = controller.syncTrustedBssidsIfEnabled() }
                }
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    RebootRecoveryCard(
        enabled = enabled,
        canEnable = canEnable,
        pendingRevokes = pendingRevokes && pendingSecureRevoke,
        busy = busy,
        notice = notice,
        switchFocus = switchFocus,
        onToggle = {
            if (busy) return@RebootRecoveryCard
            if (enabled) {
                busy = true
                scope.launch {
                    controller.disable()
                    busy = false
                    notice = null
                }
            } else {
                scope.launch {
                    canEnable = controller.canEnable()
                    if (!canEnable) {
                        notice = RecoveryNotice.NEEDS_WIFI_OR_ANDROID_11
                    } else if (controller.needsPairing()) {
                        onRequestPairing()
                    } else {
                        onRequestConfirm()
                    }
                }
            }
        },
    )
}

@Composable
fun RebootRecoveryCard(
    enabled: Boolean,
    canEnable: Boolean,
    pendingRevokes: Boolean,
    busy: Boolean,
    notice: RecoveryNotice?,
    switchFocus: FocusRequester,
    onToggle: () -> Unit,
) {
    // When ON, the toggle always stays clickable (even on Ethernet) so the user can turn it off and revoke permissions.
    val buttonEnabled = !busy && (enabled || canEnable)

    SsCard(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(stringResource(R.string.tools_reboot_title), style = SsType.Title, color = SsColors.TextPrimary)
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SsButton(
                    onClick = onToggle,
                    enabled = buttonEnabled,
                    selected = enabled,
                    modifier = Modifier.focusRequester(switchFocus),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(stringResource(R.string.tools_reboot_switch), style = SsType.Body)
                        SsPill(
                            label = stringResource(if (enabled) R.string.tools_reboot_state_on else R.string.tools_reboot_state_off),
                            background = if (enabled) SsColors.OkPillBg else SsColors.Surface,
                            text = if (enabled) SsColors.OkPillText else SsColors.TextSecondary,
                        )
                    }
                }
                if (busy) SsSpinner()
            }
            Text(
                stringResource(R.string.tools_reboot_caveat),
                style = SsType.Caption,
                color = SsColors.TextSecondary,
            )
            if (!enabled && !canEnable) {
                Text(
                    stringResource(R.string.tools_reboot_need_wifi),
                    style = SsType.Body,
                    color = SsColors.TextSecondary,
                )
            }
            if (!enabled && pendingRevokes) {
                Text(
                    stringResource(R.string.tools_reboot_pending_revoke),
                    style = SsType.Body,
                    color = SsColors.WarnPillText,
                )
            }
            noticeMessage(notice, enabled)?.let { msg ->
                Text(msg, style = SsType.Body, color = SsColors.WarnPillText)
            }
        }
    }
}

@Composable
private fun noticeMessage(notice: RecoveryNotice?, enabled: Boolean): String? = when (notice) {
    null -> null
    RecoveryNotice.NEEDS_WIFI_OR_ANDROID_11 -> if (!enabled) null else stringResource(R.string.tools_reboot_need_wifi)
    RecoveryNotice.NEEDS_LOCATION_SETTING -> stringResource(R.string.tools_reboot_location_off)
    RecoveryNotice.NEEDS_ALWAYS_ALLOW_PROMPT -> stringResource(R.string.tools_reboot_always_allow_prompt)
    RecoveryNotice.NEEDS_ALWAYS_ALLOW_TOGGLE -> stringResource(R.string.tools_reboot_always_allow_toggle)
    RecoveryNotice.PENDING_REVOKES -> stringResource(R.string.tools_reboot_pending_revoke)
    RecoveryNotice.GRANT_FAILED -> stringResource(R.string.tools_reboot_grant_failed)
    RecoveryNotice.HELPER_DOWN -> stringResource(R.string.tools_helper_down)
}

/**
 * Acknowledgment screen shown before granting reboot-recovery permissions.
 * Cancel takes initial focus so an accidental remote press never grants permissions.
 */
@Composable
fun RebootConfirmScreen(
    onTurnOn: () -> Unit,
    onCancel: () -> Unit,
) {
    BackHandler(onBack = onCancel)
    val cancelFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { cancelFocus.requestFocus() } }

    SsScreen {
        Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Text(stringResource(R.string.tools_reboot_title), style = SsType.Headline, color = SsColors.TextPrimary)
            SsCard(Modifier.fillMaxWidth()) {
                Text(
                    stringResource(R.string.tools_reboot_caveat),
                    style = SsType.Body,
                    color = SsColors.TextBody,
                    modifier = Modifier.padding(24.dp),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                SsButton(
                    label = stringResource(R.string.tools_cancel),
                    onClick = onCancel,
                    modifier = Modifier.focusRequester(cancelFocus),
                )
                SsButton(
                    label = stringResource(R.string.tools_reboot_turn_on),
                    onClick = onTurnOn,
                )
            }
        }
    }
}
