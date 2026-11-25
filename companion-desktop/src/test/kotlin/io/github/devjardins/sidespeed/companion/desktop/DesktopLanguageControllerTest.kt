package io.github.devjardins.sidespeed.companion.desktop

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class DesktopLanguageControllerTest {
    private val jvmDefault = Locale.getDefault()

    @After fun restoreDefault() {
        Locale.setDefault(jvmDefault)
    }

    @Test fun choose_sets_default_and_saves() {
        val saved = mutableListOf<String?>()
        val controller = DesktopLanguageController(Locale.US, null) { saved += it }
        assertEquals(null, controller.chosen.value)

        controller.choose("fr")
        assertEquals("fr", Locale.getDefault().language)
        assertEquals(listOf("fr"), saved)
        assertEquals("fr", controller.chosen.value)
        assertEquals(1, controller.epoch.value)

        controller.choose(null)
        assertEquals(Locale.US, Locale.getDefault())
        assertEquals(listOf("fr", null), saved)
        assertEquals(null, controller.chosen.value)
        assertEquals(2, controller.epoch.value)
    }
}
