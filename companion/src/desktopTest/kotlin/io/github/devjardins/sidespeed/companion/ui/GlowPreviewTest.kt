package io.github.devjardins.sidespeed.companion.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import org.jetbrains.skia.EncodedImageFormat

/** Renders one TV card per glow state offscreen and saves the preview PNG for the batch report. */
class GlowPreviewTest {
    @Composable
    private fun PreviewCard(label: String, glow: Glow?, dot: StatusKind, status: String) {
        SsText(label, Ss.caption)
        SsGlowCard(glow) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SsStatusDot(dot)
                SsText("Living room", Ss.heading, Modifier.weight(1f))
            }
            SsText("192.168.5.50", Ss.caption)
            SsText(status)
        }
    }

    @Test fun renderGlowPreview() {
        val scene = ImageComposeScene(width = 480, height = 660, density = Density(1f)) {
            Column(
                Modifier.background(Ss.background).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                PreviewCard("Home network (green)", Glow.GREEN, StatusKind.GOOD, "Helper running")
                PreviewCard("Tailscale (blue)", Glow.BLUE, StatusKind.GOOD, "Helper running")
                PreviewCard("Unreachable (red)", Glow.RED, StatusKind.BAD, "Offline")
                PreviewCard("Never checked (none)", null, StatusKind.UNKNOWN, "Not checked yet")
            }
        }
        try {
            val png = scene.render().encodeToData(EncodedImageFormat.PNG)!!.bytes
            val out = File(OUTPUT_PATH)
            out.parentFile.mkdirs()
            out.writeBytes(png)
            assertTrue(out.length() > 0, "preview written to $OUTPUT_PATH")
        } finally {
            scene.close()
        }
    }

    private companion object {
        const val OUTPUT_PATH =
            "/private/tmp/claude-501/-Users-mdez-Developer-SideSpeed-muse/6ef48403-4861-47d3-a0ff-a877940ea82/scratchpad/preview-glow.png"
    }
}
