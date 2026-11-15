package io.github.devjardins.sidespeed.apps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppEntryTest {
    private fun app(label: String, installer: String?) =
        AppEntry("pkg.${label.lowercase()}", label, installer, 0L)

    @Test fun storeInstallersAreNotSideloaded() {
        STORE_INSTALLERS.forEach { assertFalse(it, app("x", it).isSideloaded) }
        assertFalse(app("x", "com.android.vending").isSideloaded)
        assertFalse(app("x", "com.amazon.venezia").isSideloaded)
    }

    @Test fun nullAndPackageInstallerAreSideloaded() {
        assertTrue(app("x", null).isSideloaded)
        assertTrue(app("x", "com.google.android.packageinstaller").isSideloaded)
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
