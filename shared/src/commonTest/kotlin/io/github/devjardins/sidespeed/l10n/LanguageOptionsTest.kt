package io.github.devjardins.sidespeed.l10n

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LanguageOptionsTest {
    private val names =
        mapOf(
            "en" to "English",
            "fr" to "Français",
            "ar" to "العربية",
            "de" to "Deutsch",
            "pt-BR" to "Português (Brasil)",
            "iw" to "עברית",
        )

    private fun nameOf(tag: String): String = names.getValue(tag)

    @Test
    fun device_first_and_english_always_present() {
        val options = languageOptions(emptyList(), null, ::nameOf)
        assertEquals(listOf(null, "en"), options.map { it.tag })
        assertTrue(options.first().selected)
        options.drop(1).forEach { assertFalse(it.selected) }
    }

    @Test
    fun current_choice_marked() {
        val options = languageOptions(listOf("fr", "ar"), "fr", ::nameOf)
        assertEquals(1, options.count { it.selected })
        assertTrue(options.single { it.tag == "fr" }.selected)
    }

    @Test
    fun shipped_sorted_by_native_name_after_english() {
        val options = languageOptions(listOf("fr", "ar", "de"), null, ::nameOf)
        assertEquals(listOf(null, "en", "de", "fr", "ar"), options.map { it.tag })
    }

    @Test
    fun chosen_language_left_out_stays_selected_with_note() {
        val options = languageOptions(listOf("fr"), "ar", ::nameOf)
        val last = options.last()
        assertEquals("ar", last.tag)
        assertTrue(last.selected)
        assertTrue(last.notComplete)
        assertFalse(options.first().selected)
    }

    @Test
    fun english_chosen_is_not_duplicated() {
        val options = languageOptions(listOf("fr", "en", "de"), "en", ::nameOf)
        assertEquals(1, options.count { it.tag == "en" })
        assertTrue(options.single { it.tag == "en" }.selected)
    }

    @Test
    fun region_tag_kept() {
        val options = languageOptions(listOf("pt-BR"), null, ::nameOf)
        assertTrue(options.any { it.tag == "pt-BR" })
    }

    @Test
    fun region_variant_of_english_selects_english_with_no_note() {
        val options = languageOptions(listOf("fr"), "en-US", ::nameOf)
        assertTrue(options.single { it.tag == "en" }.selected)
        assertEquals(1, options.count { it.selected })
        assertTrue(options.none { it.notComplete })
    }

    @Test
    fun legacy_code_matches_canonical_tag() {
        val options = languageOptions(listOf("iw"), "he", ::nameOf)
        assertTrue(options.single { it.tag == "iw" }.selected)
        assertEquals(1, options.count { it.selected })
        assertTrue(options.none { it.notComplete })
    }

    @Test
    fun region_variant_matches_same_language() {
        val options = languageOptions(listOf("pt-BR"), "pt-PT", ::nameOf)
        assertTrue(options.single { it.tag == "pt-BR" }.selected)
        assertEquals(1, options.count { it.selected })
        assertTrue(options.none { it.notComplete })
    }

    @Test
    fun unknown_language_with_empty_shipped_stays_selected_with_note() {
        val options = languageOptions(emptyList(), "ar", ::nameOf)
        val last = options.last()
        assertEquals("ar", last.tag)
        assertTrue(last.selected)
        assertTrue(last.notComplete)
    }
}
