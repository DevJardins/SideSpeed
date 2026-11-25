package io.github.devjardins.sidespeed.l10n

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppLanguageTest {
    @Test
    fun locale_for_region_and_script() {
        val region = AppLanguage.localeFor("pt-BR")
        assertEquals("pt", region.language)
        assertEquals("BR", region.country)
        assertEquals("Latn", AppLanguage.localeFor("sr-Latn").script)
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
