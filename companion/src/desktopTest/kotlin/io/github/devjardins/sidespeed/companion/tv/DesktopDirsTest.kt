package io.github.devjardins.sidespeed.companion.tv

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopDirsTest {
    private val noEnv: (String) -> String? = { null }

    @Test
    fun macUsesApplicationSupport() {
        assertEquals(
            File("/Users/a/Library/Application Support/SideSpeed Companion"),
            DesktopDirs.appData("Mac OS X", "/Users/a", noEnv),
        )
    }

    @Test
    fun windowsUsesAppData() {
        assertEquals(
            File("C:\\Users\\a\\AppData\\Roaming", "SideSpeed Companion"),
            DesktopDirs.appData("Windows 11", "C:\\Users\\a") { if (it == "APPDATA") "C:\\Users\\a\\AppData\\Roaming" else null },
        )
    }

    @Test
    fun linuxUsesXdgDataHomeOrLocalShare() {
        assertEquals(File("/x/data", "sidespeed-companion"), DesktopDirs.appData("Linux", "/home/a") { if (it == "XDG_DATA_HOME") "/x/data" else null })
        assertEquals(File("/home/a/.local/share", "sidespeed-companion"), DesktopDirs.appData("Linux", "/home/a", noEnv))
    }
}
