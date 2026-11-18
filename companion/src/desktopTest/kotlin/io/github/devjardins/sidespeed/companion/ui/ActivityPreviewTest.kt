package io.github.devjardins.sidespeed.companion.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.devjardins.sidespeed.companion.store.TvStatus
import io.github.devjardins.sidespeed.companion.tv.ActivityPhase
import io.github.devjardins.sidespeed.companion.tv.SelfState
import io.github.devjardins.sidespeed.companion.tv.TvActivity
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import org.jetbrains.skia.EncodedImageFormat

/** Renders every Activity block state offscreen and saves the preview PNG for the batch report. */
class ActivityPreviewTest {
    private fun card(activity: TvActivity) = TvCardState(
        id = "tv-1",
        name = "Living room",
        address = "192.168.5.20",
        status = TvStatus.RUNNING,
        statusLabel = "Helper running",
        checkableHere = true,
        checkedAtEpochMs = null,
        note = null,
        activity = activity,
    )

    @Composable
    private fun PreviewCard(activity: TvActivity, nowMs: Long) {
        SsReachCard(Glow.GREEN) {
            SsText("Living room", Ss.heading)
            SsText("192.168.5.20", Ss.caption)
            SsText("Helper running", Ss.body)
            TvActivityBlock(card(activity), nowMs)
        }
    }

    @Test fun renderActivityPreview() {
        val now = 1_731_000_000_000L
        val compiling = TvActivity(
            phase = ActivityPhase.COMPILING, pkg = "com.example.app", label = "Example, Inc",
            mode = "speed", startedMs = System.currentTimeMillis() - 125_000L, queue = 9,
            lastPkg = "com.example.other", lastLabel = "Other", lastOk = true,
            lastMs = System.currentTimeMillis() - 600_000L, self = SelfState.PENDING, version = "0.2.1",
        )
        val idle = TvActivity(
            phase = ActivityPhase.IDLE, self = SelfState.OPTIMIZED,
            lastPkg = "org.tizentube", lastLabel = "TizenTube", lastOk = true,
            lastMs = now - 180_000L, version = "0.2.1",
        )
        val interrupted = TvActivity(
            phase = ActivityPhase.INTERRUPTED, pkg = "com.example.app", label = "Example",
            self = SelfState.PENDING, version = "0.2.1",
        )
        val scene = ImageComposeScene(width = 360, height = 1900, density = Density(1f)) {
            Column(
                Modifier.background(Ss.background).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                PreviewCard(compiling, now)
                PreviewCard(idle, now)
                PreviewCard(interrupted, now)
                PreviewCard(TvActivity(ActivityPhase.NEEDS_UPDATE), now)
                SsReachCard(Glow.GREEN) {
                    SsText("Living room", Ss.heading)
                    SsText("Just finished (ok)", Ss.caption)
                    FinishedRow(compiling.copy(lastOk = true))
                }
                SsReachCard(Glow.GREEN) {
                    SsText("Living room", Ss.heading)
                    SsText("Just finished (failed)", Ss.caption)
                    FinishedRow(compiling.copy(lastOk = false))
                }
            }
        }
        try {
            val png = scene.render().encodeToData(EncodedImageFormat.PNG)!!.bytes
            val out = File(System.getProperty("sidespeed.previewDir") ?: "build/previews", "activity.png")
            out.parentFile.mkdirs()
            out.writeBytes(png)
            assertTrue(out.length() > 0, "preview written to ${out.path}")
        } finally {
            scene.close()
        }
    }
}
