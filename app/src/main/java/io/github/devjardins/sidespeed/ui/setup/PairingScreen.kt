package io.github.devjardins.sidespeed.ui.setup

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import io.github.devjardins.sidespeed.R
import io.github.devjardins.sidespeed.adb.AdbBackend
import io.github.devjardins.sidespeed.adb.AdbPairingFailedException
import io.github.devjardins.sidespeed.data.Prefs
import io.github.devjardins.sidespeed.ui.text.UiText
import io.github.devjardins.sidespeed.ui.text.resolve
import io.github.devjardins.sidespeed.ui.text.uiText
import io.github.devjardins.sidespeed.ui.theme.SsButton
import io.github.devjardins.sidespeed.ui.theme.SsCard
import io.github.devjardins.sidespeed.ui.theme.SsColors
import io.github.devjardins.sidespeed.ui.theme.SsScreen
import io.github.devjardins.sidespeed.ui.theme.SsSpinner
import io.github.devjardins.sidespeed.ui.theme.SsType
import io.github.devjardins.sidespeed.ui.theme.bleed
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

/**
 * Standalone wireless-debugging pairing screen opened from Tools when "After a restart"
 * is toggled on before SideSpeed has its own TLS pairing.
 */
@Composable
fun PairingScreen(
    adb: AdbBackend,
    adbMutex: Mutex,
    prefs: Prefs,
    wifiIp: () -> String?,
    onPaired: () -> Unit,
    onCancel: () -> Unit,
) {
    BackHandler(onBack = onCancel)
    var code by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("") }
    var pairing by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<UiText?>(null) }
    val scope = rememberCoroutineScope()

    SsScreen {
        val scrollInset = 8.dp
        Column(
            Modifier
                .fillMaxWidth()
                .bleed(vertical = scrollInset)
                .verticalScroll(rememberScrollState())
                .padding(vertical = scrollInset),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                SsButton(stringResource(R.string.common_back), onClick = onCancel, enabled = !pairing)
                Spacer(Modifier.width(16.dp))
                Text(stringResource(R.string.setup_pairing_title), style = SsType.Headline, color = SsColors.TextPrimary)
            }
            Text(stringResource(R.string.tools_reboot_pair_first), style = SsType.Body, color = SsColors.TextBody)
            SsCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(stringResource(R.string.setup_pairing_instructions), style = SsType.Body, color = SsColors.TextBody)
                    Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(stringResource(R.string.setup_pairing_code_label), style = SsType.Caption, color = SsColors.TextSecondary)
                            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                                PairingCodeGrid(code, 6, { code = it }, focusFirst = true)
                            }
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(stringResource(R.string.setup_pairing_port_label), style = SsType.Caption, color = SsColors.TextSecondary)
                            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                                PairingCodeGrid(port, 5, { port = it }, focusFirst = false)
                            }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        SsButton(
                            label = stringResource(R.string.setup_pairing_submit),
                            enabled = !pairing,
                            onClick = {
                                val p = port.toIntOrNull()
                                if (code.length != 6 || p == null) {
                                    message = UiText.Res(R.string.setup_pairing_invalid_input)
                                    return@SsButton
                                }
                                pairing = true
                                message = UiText.Res(R.string.setup_pairing_in_progress)
                                scope.launch {
                                    val result = try {
                                        adbMutex.withLock {
                                            withTimeout(SetupViewModel.PAIR_TIMEOUT_MS) {
                                                var r = adb.pair("127.0.0.1", p, code)
                                                if (r.isFailure) wifiIp()?.let { r = adb.pair(it, p, code) }
                                                r
                                            }
                                        }
                                    } catch (_: TimeoutCancellationException) {
                                        null
                                    } finally {
                                        pairing = false
                                    }
                                    when {
                                        result == null -> message = UiText.Res(R.string.setup_pairing_timed_out)
                                        result.isSuccess -> {
                                            prefs.setPairedTls(true)
                                            onPaired()
                                        }
                                        else -> message = standalonePairFailure(result.exceptionOrNull())
                                    }
                                }
                            },
                        )
                        if (pairing) SsSpinner()
                        message?.let { Text(it.resolve(), style = SsType.Body, color = SsColors.TextBody) }
                    }
                }
            }
        }
    }
}

private fun standalonePairFailure(err: Throwable?): UiText = when (err) {
    is AdbPairingFailedException -> UiText.Res(R.string.setup_pairing_failed_adb)
    else -> {
        val msg = err?.message
        if (msg == null) {
            UiText.Res(R.string.setup_pairing_failed_unknown)
        } else {
            uiText(R.string.setup_pairing_failed, UiText.Raw(msg))
        }
    }
}
