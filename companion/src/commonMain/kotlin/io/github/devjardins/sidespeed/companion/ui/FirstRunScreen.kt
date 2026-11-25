package io.github.devjardins.sidespeed.companion.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.devjardins.sidespeed.companion.net.NetworkFingerprint
import io.github.devjardins.sidespeed.companion.resources.Res
import io.github.devjardins.sidespeed.companion.resources.first_run_body
import io.github.devjardins.sidespeed.companion.resources.first_run_not_now
import io.github.devjardins.sidespeed.companion.resources.first_run_router
import io.github.devjardins.sidespeed.companion.resources.first_run_tailnet
import io.github.devjardins.sidespeed.companion.resources.first_run_title
import io.github.devjardins.sidespeed.companion.resources.first_run_trust
import io.github.devjardins.sidespeed.companion.resources.first_run_use_tailscale
import io.github.devjardins.sidespeed.companion.ui.text.UiText
import io.github.devjardins.sidespeed.companion.ui.text.resolve
import org.jetbrains.compose.resources.stringResource

@Composable
fun FirstRunScreen(
    network: NetworkFingerprint?,
    tailnet: TailnetState?,
    onTrust: () -> Unit,
    onTrustTailnet: () -> Unit,
    onNotNow: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().padding(28.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp, Alignment.CenterVertically),
    ) {
        SsLogoMark(56.dp)
        SsText(stringResource(Res.string.first_run_title), Ss.title)
        SsText(stringResource(Res.string.first_run_body))
        if (network != null) {
            SsCard {
                SsText(
                    UiText.Res(
                        Res.string.first_run_router,
                        listOf(UiText.Ltr(network.gatewayIp), UiText.Ltr(network.subnet)),
                    ).resolve(),
                    Ss.caption,
                )
            }
        }
        if (tailnet != null && tailnet.ready) {
            SsCard {
                SsText(
                    UiText.Res(Res.string.first_run_tailnet, listOf(UiText.Fsi(tailnet.name))).resolve(),
                    Ss.caption,
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (network != null) SsButton(stringResource(Res.string.first_run_trust), onTrust, primary = true)
            if (tailnet != null && tailnet.ready) {
                SsButton(stringResource(Res.string.first_run_use_tailscale), onTrustTailnet, primary = network == null)
            }
            SsButton(stringResource(Res.string.first_run_not_now), onNotNow)
        }
    }
}
