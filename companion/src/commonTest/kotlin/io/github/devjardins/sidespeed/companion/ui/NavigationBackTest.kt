package io.github.devjardins.sidespeed.companion.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NavigationBackTest {
    @Test fun addGoesBackHome() = assertEquals(Page.HOME, Page.ADD.up())

    @Test fun settingsGoesBackHome() = assertEquals(Page.HOME, Page.SETTINGS.up())

    @Test fun aboutGoesBackToSettings() = assertEquals(Page.SETTINGS, Page.ABOUT.up())

    @Test fun homeHasNothingAbove() = assertNull(Page.HOME.up())
}
