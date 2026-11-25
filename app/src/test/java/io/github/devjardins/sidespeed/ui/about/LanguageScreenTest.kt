package io.github.devjardins.sidespeed.ui.about

import io.github.devjardins.sidespeed.R
import io.github.devjardins.sidespeed.l10n.LanguageOption
import io.github.devjardins.sidespeed.testing.english
import io.github.devjardins.sidespeed.ui.text.uiText
import org.junit.Assert.assertEquals
import org.junit.Test

class LanguageScreenTest {
    @Test fun device_row_uses_device_label() {
        val option = LanguageOption(tag = null, name = null, selected = true, notComplete = false)
        assertEquals("Same as device", languageRowLabel(option, "Same as device"))
    }

    @Test fun language_row_uses_native_name() {
        val option = LanguageOption(tag = "fr", name = "Français", selected = false, notComplete = false)
        assertEquals("Français", languageRowLabel(option, "Same as device"))
    }

    @Test fun english_strings_resolve_exactly() {
        assertEquals("Language", uiText(R.string.about_language).english())
        assertEquals("Same as device", uiText(R.string.about_language_same_as_device).english())
        assertEquals(
            "Not complete in this version. Showing English for now.",
            uiText(R.string.about_language_not_complete).english(),
        )
        assertEquals("✓ Français", uiText(R.string.about_language_selected, "Français").english())
    }
}
