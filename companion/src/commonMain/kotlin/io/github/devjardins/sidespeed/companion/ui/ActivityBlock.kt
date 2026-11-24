package io.github.devjardins.sidespeed.companion.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.devjardins.sidespeed.companion.store.TvStatus
import io.github.devjardins.sidespeed.companion.tv.ActivityPhase
import io.github.devjardins.sidespeed.companion.tv.SelfState
import io.github.devjardins.sidespeed.companion.tv.TvActivity
import io.github.devjardins.sidespeed.companion.tv.formatActivityElapsed
import io.github.devjardins.sidespeed.companion.tv.lastCaption
import io.github.devjardins.sidespeed.companion.ui.text.resolve
import kotlinx.coroutines.delay

/**
 * The live compile-status block inside a TV card. Hidden unless the TV is checkable here and its
 * helper is running, so stale live data never shows. The caller passes the screen's clock for the
 * "Last: … ago" caption; the compiling row ticks every second itself.
 */
@Composable
fun TvActivityBlock(tv: TvCardState, nowMs: Long) {
    val a = tv.activity ?: return
    if (!tv.checkableHere || tv.status != TvStatus.RUNNING) return
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.fillMaxWidth().padding(top = 2.dp).height(1.dp).background(Ss.textPrimary.copy(alpha = 0.08f)))
        if (a.phase != ActivityPhase.NEEDS_UPDATE && a.self != SelfState.DEBUG) SelfRow(a)
        when (a.phase) {
            ActivityPhase.NEEDS_UPDATE ->
                SsText("Update SideSpeed on the TV to see live compiles.", Ss.caption)
            ActivityPhase.COMPILING -> CompilingRow(a)
            ActivityPhase.WAITING_HELPER -> WaitingRow(a)
            ActivityPhase.INTERRUPTED -> InterruptedRow(a)
            ActivityPhase.IDLE -> IdleRow(a, nowMs)
            ActivityPhase.UNAVAILABLE -> Unit
        }
    }
}

/** The running compile, with a 2.5 s celebration when a new finished result arrives. */
@Composable
private fun CompilingRow(a: TvActivity) {
    var seenLastMs by remember { mutableStateOf<Long?>(null) }
    var celebration by remember { mutableStateOf<TvActivity?>(null) }
    val reduceMotion = isReduceMotion()
    LaunchedEffect(a.lastMs, a.lastOk) {
        val key = if (a.lastMs > 0L && a.lastOk != null) a.lastMs else null
        if (key != null && seenLastMs != null && seenLastMs != key) {
            celebration = a
            delay(2_500)
            celebration = null
        }
        if (key != null) seenLastMs = key
    }
    val done = celebration
    val morphMs = if (reduceMotion) 0 else 250
    AnimatedContent(
        targetState = done != null,
        transitionSpec = { fadeIn(tween(morphMs)) togetherWith fadeOut(tween(morphMs)) },
        label = "activity-done",
    ) { finished ->
        if (finished && done != null) FinishedRow(done) else LiveCompilingRow(a)
    }
    if (done == null && a.queue > 0) SsText("${a.queue} more waiting", Ss.caption)
}

@Composable
private fun LiveCompilingRow(a: TvActivity) {
    val tick by produceState(System.currentTimeMillis(), a.pkg, a.startedMs) {
        while (true) {
            delay(1_000)
            value = System.currentTimeMillis()
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SsSpinner(18.dp, stroke = 2.dp)
        SsText(a.label.ifEmpty { a.pkg }, Ss.body, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        SsText(
            formatActivityElapsed(tick - a.startedMs),
            Ss.caption.copy(fontFamily = FontFamily.Monospace),
            maxLines = 1,
        )
    }
}

/** The 2.5 s celebration when a new finished result arrives; internal for the preview test. */
@Composable
internal fun FinishedRow(done: TvActivity) {
    val ok = done.lastOk == true
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (ok) CheckCircle(Ss.accent, Ss.surface) else WarnMark()
        val label = done.lastLabel.ifEmpty { done.lastPkg }
        SsText(
            if (ok) label else "Couldn't compile $label",
            Ss.body,
            Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun WaitingRow(a: TvActivity) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SsStatusDot(StatusKind.WAITING)
        SsText("Waiting for the helper", Ss.body, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    if (a.queue > 0) SsText("${a.queue} more waiting", Ss.caption)
}

@Composable
private fun InterruptedRow(a: TvActivity) {
    val label = a.label.ifEmpty { a.pkg }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        WarnMark()
        SsText("$label was interrupted", Ss.body, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    SsText("SideSpeed retries it when the helper is back.", Ss.caption)
}

@Composable
private fun IdleRow(a: TvActivity, nowMs: Long) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(18.dp).alpha(0.6f), contentAlignment = Alignment.Center) {
            CheckPath(Ss.textSecondary, 18.dp)
        }
        SsText("All caught up", Ss.body, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    lastCaption(a.lastLabel.ifEmpty { a.lastPkg }, a.lastMs, nowMs)?.let { SsText(it.resolve(), Ss.caption) }
}

@Composable
private fun SelfRow(a: TvActivity) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SsLogoMark(16.dp)
            SsText("SideSpeed", Ss.body, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (a.selfCompiling) {
                SsSpinner(14.dp)
                SsText("Optimizing…", Ss.body, maxLines = 1)
            } else if (a.self == SelfState.OPTIMIZED) {
                Pill("Optimized", Ss.okBg, Ss.okText)
            } else {
                Pill("Not optimized yet", Ss.button, Ss.textSecondary)
            }
        }
        if (!a.selfCompiling && a.self == SelfState.PENDING) {
            SsText("It optimizes itself when you leave it on the TV.", Ss.caption)
        }
    }
}

@Composable
private fun Pill(text: String, bg: Color, fg: Color) {
    Box(Modifier.background(bg, RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 4.dp)) {
        SsText(text, Ss.caption.copy(color = fg), maxLines = 1)
    }
}

/** Accent disc with a check drawn as a Canvas path. */
@Composable
private fun CheckCircle(bg: Color, mark: Color, size: Dp = 18.dp) {
    Box(Modifier.size(size).background(bg, CircleShape), contentAlignment = Alignment.Center) {
        CheckPath(mark, size)
    }
}

@Composable
private fun CheckPath(mark: Color, size: Dp) {
    Canvas(Modifier.size(size)) {
        val s = size.toPx()
        drawPath(
            Path().apply {
                moveTo(s * 0.28f, s * 0.54f)
                lineTo(s * 0.45f, s * 0.70f)
                lineTo(s * 0.74f, s * 0.32f)
            },
            mark,
            style = Stroke(s * 0.12f, cap = StrokeCap.Round),
        )
    }
}

/** Warn disc with a "!". */
@Composable
private fun WarnMark(size: Dp = 18.dp) {
    Box(Modifier.size(size).background(Ss.warnBg, CircleShape), contentAlignment = Alignment.Center) {
        SsText("!", Ss.caption.copy(color = Ss.warnText))
    }
}
