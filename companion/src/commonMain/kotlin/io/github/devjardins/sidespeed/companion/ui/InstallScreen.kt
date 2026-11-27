package io.github.devjardins.sidespeed.companion.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import io.github.devjardins.sidespeed.companion.resources.Res
import io.github.devjardins.sidespeed.companion.resources.common_back
import io.github.devjardins.sidespeed.companion.resources.common_cancel
import io.github.devjardins.sidespeed.companion.resources.common_try_again
import io.github.devjardins.sidespeed.companion.resources.install_button
import io.github.devjardins.sidespeed.companion.resources.install_checking
import io.github.devjardins.sidespeed.companion.resources.install_committing
import io.github.devjardins.sidespeed.companion.resources.install_fresh
import io.github.devjardins.sidespeed.companion.resources.install_keep_optimized
import io.github.devjardins.sidespeed.companion.resources.install_progress_line
import io.github.devjardins.sidespeed.companion.resources.install_splits_summary
import io.github.devjardins.sidespeed.companion.resources.install_starting_compile
import io.github.devjardins.sidespeed.companion.resources.install_store_warning_amazon
import io.github.devjardins.sidespeed.companion.resources.install_store_warning_play
import io.github.devjardins.sidespeed.companion.resources.install_system_warning
import io.github.devjardins.sidespeed.companion.resources.install_tailscale_note
import io.github.devjardins.sidespeed.companion.resources.install_title
import io.github.devjardins.sidespeed.companion.resources.install_update_from
import io.github.devjardins.sidespeed.companion.ui.text.UiText
import io.github.devjardins.sidespeed.companion.ui.text.resolve
import org.jetbrains.compose.resources.stringResource

private const val BYTES_PER_MB = 1_000_000.0

/**
 * The one install in progress: review, transfer, finishing, or a failure. [onCancel] is also Back; it is
 * offered only while the phase allows it, so nothing can be cancelled once the TV is committing.
 */
@Composable
fun InstallScreen(
    session: InstallSessionUi,
    onKeepOptimizedChange: (Boolean) -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
) {
    val phase = session.phase
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Header(
            UiText.Res(Res.string.install_title, listOf(UiText.Fsi(session.tvName))).resolve(),
            onBack = if (phase.canCancel) onCancel else null,
        )
        SsCard {
            when (phase) {
                InstallPhase.Inspecting -> Working(UiText.Res(Res.string.install_checking))
                is InstallPhase.Review -> ReviewBody(session, phase.review, onKeepOptimizedChange)
                is InstallPhase.Transferring -> {
                    val mb = { bytes: Long -> UiText.Decimal(bytes / BYTES_PER_MB, 1) }
                    SsText(
                        UiText.Res(Res.string.install_progress_line, listOf(mb(phase.bytesSent), mb(phase.totalBytes))).resolve(),
                        Ss.body,
                    )
                    ProgressBar(if (phase.totalBytes > 0) phase.bytesSent.toFloat() / phase.totalBytes else 0f)
                    if (session.isTailnet) SsText(stringResource(Res.string.install_tailscale_note), Ss.caption)
                }
                InstallPhase.Committing -> Working(UiText.Res(Res.string.install_committing))
                InstallPhase.StartingCompile -> Working(UiText.Res(Res.string.install_starting_compile))
                is InstallPhase.Failed ->
                    SsText(phase.message.resolve(), Ss.body.copy(color = Ss.dangerText))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            when (phase) {
                is InstallPhase.Review -> {
                    SsButton(stringResource(Res.string.install_button), onConfirm, primary = true)
                    SsButton(stringResource(Res.string.common_cancel), onCancel)
                }
                is InstallPhase.Failed -> {
                    if (phase.canRetry) SsButton(stringResource(Res.string.common_try_again), onRetry, primary = true)
                    SsButton(stringResource(Res.string.common_back), onCancel)
                }
                InstallPhase.Committing, InstallPhase.StartingCompile ->
                    SsButton(stringResource(Res.string.common_cancel), {}, enabled = false)
                else -> SsButton(stringResource(Res.string.common_cancel), onCancel)
            }
        }
    }
}

@Composable
private fun Working(text: UiText) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        SsSpinner()
        SsText(text.resolve(), Ss.body)
    }
}

@Composable
private fun ReviewBody(session: InstallSessionUi, review: InstallReview, onKeepOptimizedChange: (Boolean) -> Unit) {
    SsText(review.appName, Ss.heading)
    SsText(UiText.Ltr(review.packageName).resolve(), Ss.caption)
    val line = review.installedVersion?.let {
        UiText.Res(Res.string.install_update_from, listOf(UiText.Fsi(review.appName), UiText.Ltr(it), UiText.Ltr(review.newVersion)))
    } ?: UiText.Res(Res.string.install_fresh, listOf(UiText.Fsi(review.appName), UiText.Ltr(review.newVersion)))
    SsText(line.resolve(), Ss.body)
    if (review.summaryTags.isNotEmpty()) {
        SsText(UiText.Res(Res.string.install_splits_summary, listOf(UiText.Ltr(review.summaryTags.joinToString(", ")))).resolve(), Ss.caption)
    }
    val warnings = listOfNotNull(
        when (review.store) {
            InstallStoreSource.GOOGLE_PLAY -> Res.string.install_store_warning_play
            InstallStoreSource.AMAZON_APPSTORE -> Res.string.install_store_warning_amazon
            null -> null
        },
        if (review.isSystemApp) Res.string.install_system_warning else null,
    )
    for (w in warnings) {
        SsText(
            stringResource(w),
            Ss.body.copy(color = Ss.warnText),
            Modifier.fillMaxWidth().background(Ss.warnBg, RoundedCornerShape(12.dp)).padding(12.dp),
        )
    }
    if (session.showKeepToggle) {
        SsCheckRow(stringResource(Res.string.install_keep_optimized), session.keepOptimized, onKeepOptimizedChange)
    }
}

/** A rounded bar: [Ss.accent] fill over an [Ss.button] track. [fraction] is clamped to 0..1. */
@Composable
private fun ProgressBar(fraction: Float) {
    val shape = RoundedCornerShape(4.dp)
    Box(Modifier.fillMaxWidth().height(8.dp).clip(shape).background(Ss.button)) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(fraction.coerceIn(0f, 1f)).background(Ss.accent, shape))
    }
}
