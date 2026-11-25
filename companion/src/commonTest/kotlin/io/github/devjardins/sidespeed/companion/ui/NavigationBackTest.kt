package io.github.devjardins.sidespeed.companion.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NavigationBackTest {
    @Test fun addGoesBackHome() = assertEquals(Page.HOME, Page.ADD.up())

    @Test fun settingsGoesBackHome() = assertEquals(Page.HOME, Page.SETTINGS.up())

    @Test fun aboutGoesBackToSettings() = assertEquals(Page.SETTINGS, Page.ABOUT.up())

    @Test fun languageGoesBackToSettings() = assertEquals(Page.SETTINGS, Page.LANGUAGE.up())

    @Test fun homeHasNothingAbove() = assertNull(Page.HOME.up())

    @Test fun goingUpFromLanguageFocusesTheLanguageButton() {
        val nav = CompanionNav(Page.LANGUAGE)
        nav.goUp()
        assertEquals(Page.SETTINGS, nav.page)
        assertTrue(nav.focusLanguage)
    }

    @Test fun goingUpFromAboutLeavesLanguageFocusAlone() {
        val nav = CompanionNav(Page.ABOUT)
        nav.goUp()
        assertEquals(Page.SETTINGS, nav.page)
        assertFalse(nav.focusLanguage)
    }

    @Test fun goingUpFromHomeStaysHome() {
        val nav = CompanionNav(Page.HOME)
        nav.goUp()
        assertEquals(Page.HOME, nav.page)
    }
}
