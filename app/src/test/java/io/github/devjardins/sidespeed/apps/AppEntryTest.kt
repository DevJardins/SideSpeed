package io.github.devjardins.sidespeed.apps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppEntryTest {
    private fun app(label: String, installer: String?, system: Boolean = false) =
        AppEntry("pkg.${label.lowercase()}", label, installer, 0L, isSystem = system)

    @Test fun storeInstallersAreNotSideloaded() {
        STORE_INSTALLERS.forEach { assertFalse(it, app("x", it).isSideloaded) }
        assertFalse(app("x", "com.android.vending").isSideloaded)
        assertFalse(app("x", "com.amazon.venezia").isSideloaded)
    }

    @Test fun nullAndPackageInstallerAreSideloaded() {
        assertTrue(app("x", null).isSideloaded)
        assertTrue(app("x", "com.google.android.packageinstaller").isSideloaded)
    }

    @Test fun systemAppWithNullInstallerIsNotSideloaded() {
        assertFalse(app("x", null, system = true).isSideloaded)
        assertFalse(app("x", "com.google.android.packageinstaller", system = true).isSideloaded)
    }

    @Test fun nonSystemNullInstallerIsSideloaded() {
        assertTrue(app("x", null, system = false).isSideloaded)
    }

    @Test fun systemAppsSortWithStoreApps() {
        val sorted = sortForPicker(listOf(app("Amazon", null, system = true), app("Zed", null), app("Beta", "com.android.vending")))
        assertEquals(listOf("Zed", "Amazon", "Beta"), sorted.map { it.label })
    }

    @Test fun sortsSideloadedFirstThenLabelCaseInsensitive() {
        val sorted = sortForPicker(
            listOf(
                app("Alpha", "com.android.vending"),
                app("zeta", null),
                app("Beta", null),
                app("aardvark", "com.amazon.venezia"),
            ),
        )
        assertEquals(listOf("Beta", "zeta", "aardvark", "Alpha"), sorted.map { it.label })
    }
}
