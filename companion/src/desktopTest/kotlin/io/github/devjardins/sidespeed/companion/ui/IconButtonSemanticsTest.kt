package io.github.devjardins.sidespeed.companion.ui

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import kotlin.test.Test
import kotlin.test.assertEquals

/** An icon-only button must carry its name and role, so screen readers announce "Settings, button". */
class IconButtonSemanticsTest {
    private fun SemanticsNode.all(): List<SemanticsNode> = listOf(this) + children.flatMap { it.all() }

    @Test fun iconButtonIsANamedButton() {
        val scene = ImageComposeScene(width = 100, height = 100, density = Density(1f)) {
            SsIconButton({}, "Settings") { SsGlyph(Glyph.SETTINGS) }
        }
        try {
            scene.render()
            val nodes = scene.semanticsOwners.flatMap { it.rootSemanticsNode.all() }
            val named = nodes.filter {
                it.config.getOrNull(SemanticsProperties.ContentDescription) == listOf("Settings")
            }
            assertEquals(1, named.size, "one node named Settings")
            assertEquals(Role.Button, named.single().config.getOrNull(SemanticsProperties.Role))
        } finally {
            scene.close()
        }
    }
}
