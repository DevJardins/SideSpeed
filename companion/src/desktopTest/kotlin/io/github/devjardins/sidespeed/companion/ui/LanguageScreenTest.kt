package io.github.devjardins.sidespeed.companion.ui

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsConfiguration
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.unit.Density
import io.github.devjardins.sidespeed.companion.l10n.LanguageController
import io.github.devjardins.sidespeed.companion.resources.Res
import io.github.devjardins.sidespeed.companion.resources.settings_language
import io.github.devjardins.sidespeed.companion.resources.settings_language_not_complete
import io.github.devjardins.sidespeed.companion.resources.settings_language_same_as_device
import io.github.devjardins.sidespeed.companion.resources.settings_language_selected
import io.github.devjardins.sidespeed.companion.testing.english
import io.github.devjardins.sidespeed.companion.ui.text.UiText
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LanguageScreenTest {
    private class FakeLanguageController(
        chosen: String?,
        override val shipped: List<String> = emptyList(),
    ) : LanguageController {
        private val _chosen = MutableStateFlow(chosen)
        override val chosen: StateFlow<String?> = _chosen
        override val needsRestart: Boolean = false
        val recorded = mutableListOf<String?>()
        override fun choose(tag: String?) {
            recorded += tag
        }
    }

    private fun renderLanguage(controller: FakeLanguageController): ImageComposeScene {
        val scene = ImageComposeScene(width = 420, height = 900, density = Density(1f)) {
            LanguageScreen(controller = controller, onBack = {})
        }
        scene.render()
        scene.render()
        return scene
    }

    private fun <T> SemanticsConfiguration.valueOrNull(key: SemanticsPropertyKey<T>): T? =
        if (contains(key)) get(key) else null

    private fun SemanticsNode.subtreeText(): String {
        val own = config.valueOrNull(SemanticsProperties.Text)
            ?.joinToString(separator = "") { it.text }.orEmpty()
        return buildString {
            append(own)
            children.forEach { append(it.subtreeText()) }
        }
    }

    private fun ImageComposeScene.allNodes(): List<SemanticsNode> {
        val out = mutableListOf<SemanticsNode>()
        fun visit(node: SemanticsNode) {
            out += node
            node.children.forEach(::visit)
        }
        semanticsOwners.forEach { visit(it.rootSemanticsNode) }
        return out
    }

    @Test
    fun opens_with_focus_on_current_choice() {
        val scene = renderLanguage(FakeLanguageController(chosen = "fr"))
        try {
            val focused = scene.allNodes()
                .filter { it.config.valueOrNull(SemanticsProperties.Focused) == true }
            assertEquals(1, focused.size, "exactly one row is focused, got ${focused.size}")
            assertEquals("✓ Français", focused.single().subtreeText())
        } finally {
            scene.close()
        }
    }

    @Test
    fun choosing_calls_controller() {
        val controller = FakeLanguageController(chosen = "fr")
        val scene = renderLanguage(controller)
        try {
            val device = scene.allNodes().firstOrNull { node ->
                node.config.valueOrNull(SemanticsActions.OnClick) != null &&
                    node.subtreeText() == "Same as device"
            }
            assertNotNull(device, "a Language row reads Same as device")
            val clicked = device.config.valueOrNull(SemanticsActions.OnClick)?.action?.invoke()
            assertTrue(clicked == true, "the Same as device row handles the click")
            assertEquals(listOf<String?>(null), controller.recorded)
        } finally {
            scene.close()
        }
    }

    @Test
    fun not_complete_note_shown() {
        val scene = renderLanguage(FakeLanguageController(chosen = "ar"))
        try {
            val texts = scene.allNodes().map { it.subtreeText() }
            assertTrue(
                texts.any { it.contains("Not complete in this version. Showing English for now.") },
                "the unfinished-language note is shown",
            )
        } finally {
            scene.close()
        }
    }

    @Test
    fun english_strings_resolve_exactly() {
        assertEquals("Language", UiText.Res(Res.string.settings_language).english())
        assertEquals("Same as device", UiText.Res(Res.string.settings_language_same_as_device).english())
        assertEquals(
            "Not complete in this version. Showing English for now.",
            UiText.Res(Res.string.settings_language_not_complete).english(),
        )
        assertEquals(
            "✓ Français",
            UiText.Res(Res.string.settings_language_selected, listOf("Français")).english(),
        )
    }
}
