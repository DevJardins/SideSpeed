package io.github.devjardins.sidespeed.ui.about

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import io.github.devjardins.sidespeed.tools.ManualCheckResult
import io.github.devjardins.sidespeed.tools.UpdateCheck
import io.github.devjardins.sidespeed.tools.displayVersion
import io.github.devjardins.sidespeed.ui.theme.SsButton
import io.github.devjardins.sidespeed.ui.theme.SsCard
import io.github.devjardins.sidespeed.ui.theme.SsColors
import io.github.devjardins.sidespeed.ui.theme.SsDimens
import io.github.devjardins.sidespeed.ui.theme.SsScreen
import io.github.devjardins.sidespeed.ui.theme.SsType
import io.github.devjardins.sidespeed.ui.theme.bleed
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** The About screen: version, a manual update check, credits, and the Ko-fi QR. */
@Composable
fun AboutScreen(
    versionName: String,
    check: UpdateCheck,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    // Give the D-pad a starting point: the Back button takes focus when the screen opens.
    val backFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { backFocus.requestFocus() } }
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<ManualCheckResult?>(null) }
    // Focus state via the interaction source (the same channel tv-material buttons
    // use), driving the QR frame's ring below.
    val qrInteraction = remember { MutableInteractionSource() }
    val qrFocused by qrInteraction.collectIsFocusedAsState()
    // Encoded once, not per recomposition.
    val qr = rememberQrBitmap(KOFI_URL)
    SsScreen {
        // Same scroll treatment as ToolsScreen: bleed the column so the focus ring
        // is never clipped at the edges.
        val scrollInset = 8.dp
        Column(
            Modifier
                .fillMaxSize()
                .bleed(horizontal = scrollInset, vertical = scrollInset)
                .verticalScroll(rememberScrollState())
                .padding(scrollInset),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                SsButton("‹ Back", onClick = onBack, modifier = Modifier.focusRequester(backFocus))
                Spacer(Modifier.width(16.dp))
                Text("About", style = SsType.Headline, color = SsColors.TextPrimary)
            }
            SsCard(Modifier.fillMaxWidth()) {
                Column(
                    Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("SideSpeed", style = SsType.Title, color = SsColors.TextPrimary)
                    Text("Version $versionName", style = SsType.Body, color = SsColors.TextBody)
                    SsButton(
                        "Check for updates",
                        onClick = {
                            checking = true
                            scope.launch(Dispatchers.IO) {
                                result = check.checkNow()
                                checking = false
                            }
                        },
                        enabled = !checking,
                    )
                    val line = when {
                        checking -> "Checking…"
                        result is ManualCheckResult.UpToDate -> "You have the latest version."
                        result is ManualCheckResult.Newer ->
                            "SideSpeed ${displayVersion((result as ManualCheckResult.Newer).tag)} is out. " +
                                "The update notice is on the Apps screen."
                        result is ManualCheckResult.Failed -> "Couldn't reach GitHub. Try again later."
                        else -> null
                    }
                    line?.let {
                        Text(it, style = SsType.Body, color = SsColors.TextBody)
                    }
                }
            }
            SsCard(Modifier.fillMaxWidth()) {
                Column(
                    Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("Made with ♥ by DevJardins", style = SsType.Body, color = SsColors.TextBody)
                    Text("devjardins.com", style = SsType.Body, color = SsColors.TextBody)
                    Text(
                        "Support SideSpeed on Ko-fi",
                        style = SsType.Title,
                        color = SsColors.TextPrimary,
                    )
                    Text("ko-fi.com/devjardins", style = SsType.Body, color = SsColors.TextBody)
                    // Focusable with no action: this lets the D-pad scroll the QR
                    // into view, like any other focused content on this screen.
                    // The Box sizes itself around the fixed image, so the focus
                    // ring draws outside the QR instead of over its modules.
                    Box(
                        Modifier
                            .focusable(interactionSource = qrInteraction)
                            .border(
                                BorderStroke(
                                    SsDimens.FocusRing,
                                    if (qrFocused) SsColors.Accent else Color.Transparent,
                                ),
                                RoundedCornerShape(6.dp),
                            )
                            .padding(SsDimens.FocusRing),
                    ) {
                        Image(
                            qr,
                            contentDescription = "QR code for ko-fi.com/devjardins",
                            modifier = Modifier.size(160.dp),
                        )
                    }
                    Text(
                        "Scan with your phone.",
                        style = SsType.Caption,
                        color = SsColors.TextSecondary,
                    )
                }
            }
        }
    }
}
