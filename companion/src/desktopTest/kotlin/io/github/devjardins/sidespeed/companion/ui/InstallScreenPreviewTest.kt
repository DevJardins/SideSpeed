package io.github.devjardins.sidespeed.companion.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsConfiguration
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import io.github.devjardins.sidespeed.companion.store.TvStatus
import io.github.devjardins.sidespeed.companion.tv.ActivityPhase
import io.github.devjardins.sidespeed.companion.tv.TvActivity
import io.github.devjardins.sidespeed.companion.ui.text.UiText
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.jetbrains.skia.EncodedImageFormat

/** Renders every install screen state and the Home card controls offscreen; saves preview PNGs. */
class InstallScreenPreviewTest {
    private val review = InstallReview(
        appName = "Example", packageName = "com.example.app", newVersion = "2.0", installedVersion = "1.0",
        summaryTags = listOf("arm64", "xhdpi"), totalBytes = 52_000_000L,
        store = InstallStoreSource.GOOGLE_PLAY, isSystemApp = false,
    )

    private fun session(phase: InstallPhase, tailnet: Boolean = false) = InstallSessionUi(
        tvId = "tv-1", tvName = "Living room", isTailnet = tailnet, phase = phase,
        keepOptimized = true, showKeepToggle = true,
    )

    private fun phases() = listOf(
        "review" to session(InstallPhase.Review(review)),
        "transferring" to session(InstallPhase.Transferring(10_000_000L, 52_000_000L, 2_000_000L), tailnet = true),
        "committing" to session(InstallPhase.Committing),
        "failed" to session(InstallPhase.Failed(UiText.Raw("The install didn't finish."), canRetry = true)),
    )

    private fun <T> SemanticsConfiguration.valueOrNull(key: SemanticsPropertyKey<T>): T? =
        if (contains(key)) get(key) else null

    private fun ImageComposeScene.texts(): List<String> {
        val out = mutableListOf<String>()
        fun visit(n: SemanticsNode) {
            n.config.valueOrNull(SemanticsProperties.Text)?.let { out += it.joinToString("") { t -> t.text } }
            n.children.forEach(::visit)
        }
        semanticsOwners.forEach { visit(it.rootSemanticsNode) }
        return out
    }

    private fun renderPng(name: String, dir: LayoutDirection, content: @androidx.compose.runtime.Composable () -> Unit): List<String> {
        val scene = ImageComposeScene(width = 420, height = 900, density = Density(1f)) {
            CompositionLocalProvider(LocalLayoutDirection provides dir) { content() }
        }
        try {
            scene.render()
            val png = scene.render().encodeToData(EncodedImageFormat.PNG)!!.bytes
            val out = File(System.getProperty("sidespeed.previewDir") ?: "build/previews", "$name.png")
            out.parentFile.mkdirs()
            out.writeBytes(png)
            assertTrue(out.length() > 0)
            return scene.texts()
        } finally {
            scene.close()
        }
    }

    @Test fun installScreenRendersEveryPhaseLtrAndRtl() {
        for ((phaseName, s) in phases()) for (dir in LayoutDirection.values()) {
            val texts = renderPng("install-$phaseName-$dir", dir) { InstallScreen(s, {}, {}, {}, {}) }
            assertTrue(texts.isNotEmpty(), "$phaseName $dir has text")
            if (phaseName == "review") {
                assertTrue(texts.any { it.contains("Google Play") }, "play caveat")
                assertTrue(texts.any { it.contains("Includes:") && it.contains("arm64") }, "split summary")
                assertTrue(texts.any { it.contains("Keep this app optimized") }, "keep checkbox")
            }
            if (phaseName == "transferring") assertTrue(texts.any { it.contains("Tailscale") }, "tailscale note")
        }
    }

    private fun card(activity: TvActivity?) = TvCardState(
        id = "tv-1", name = "Living room", address = "192.168.5.20", status = TvStatus.RUNNING,
        statusLabel = Copy.statusLabel(TvStatus.RUNNING), checkableHere = true, checkedAtEpochMs = null,
        note = null, activity = activity,
    )

    private fun home(activity: TvActivity?, highlight: String? = null, hint: Boolean = false) =
        renderPng("home-${activity?.phase}-$highlight-$hint", LayoutDirection.Ltr) {
            HomeScreen(
                CompanionUiState(loaded = true, tvs = listOf(card(activity))),
                onRestart = {}, onAdd = {}, onSettings = {},
                dropHighlightTvId = highlight, dropHintVisible = hint,
            )
        }

    @Test fun homeShowsInstallAndOptimizeControls() {
        val idle = TvActivity(ActivityPhase.IDLE, remoteSupported = true)
        val texts = home(idle)
        assertTrue("Install an app" in texts)
        assertTrue("Optimize now" in texts)
        assertEquals(0, home(idle.copy(phase = ActivityPhase.COMPILING)).count { it == "Optimize now" })
        assertTrue("Drop an app file here to install it on this TV." in home(idle, highlight = "tv-1", hint = true))
        assertTrue("Drop an app file here to install it on this TV." !in home(idle))
    }
}
