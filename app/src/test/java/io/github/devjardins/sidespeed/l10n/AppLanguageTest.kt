package io.github.devjardins.sidespeed.l10n

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

class AppLanguageTest {
    @Test
    fun locale_for_region_and_script() {
        val region = AppLanguage.localeFor("pt-BR")
        assertEquals("pt", region.language)
        assertEquals("BR", region.country)
        assertEquals("Latn", AppLanguage.localeFor("sr-Latn").script)
    }

    @Test
    fun effective_locale_falls_back_to_us_english() {
        assertEquals(Locale.US, AppLanguage.effectiveLocale("ar", emptyList()))
    }

    @Test
    fun effective_locale_keeps_shipped_pseudo_tag() {
        assertEquals(
            Locale.forLanguageTag("ar-XB"),
            AppLanguage.effectiveLocale("ar-XB", listOf("ar-XB")),
        )
    }

    @Test
    fun fake_store_round_trip() {
        val store: LanguageStore = object : LanguageStore {
            private var tag: String? = null
            override fun chosen(): String? = tag
            override fun setChosen(tag: String?) {
                this.tag = tag
            }
        }
        store.setChosen("ar")
        assertEquals("ar", store.chosen())
        store.setChosen(null)
        assertNull(store.chosen())
    }
}
