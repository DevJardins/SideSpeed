package io.github.devjardins.sidespeed.companion.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutostartTest {
    @Test fun macPlistEscapesPathAndRunsAtLoad() {
        val p = Autostart.macPlist("/Applications/A&B <x>/SideSpeed Companion")
        assertTrue(p.contains("<string>io.github.devjardins.sidespeed.companion</string>"))
        assertTrue(p.contains("/Applications/A&amp;B &lt;x&gt;/SideSpeed Companion"))
        assertTrue(p.contains("<key>RunAtLoad</key><true/>"))
    }

    @Test fun linuxEntryQuotesExec() {
        assertTrue(Autostart.linuxDesktopEntry("/opt/side speed/bin/app").contains("Exec=\"/opt/side speed/bin/app\""))
    }

    @Test fun windowsValueIsQuoted() {
        assertEquals("\"C:\\Program Files\\SideSpeed\\app.exe\"", Autostart.windowsRunValue("C:\\Program Files\\SideSpeed\\app.exe"))
    }

    @Test fun enablingWithoutAPackagedLauncherWritesNothing() {
        // Run from Gradle there is no jpackage.app-path, so there is nothing to launch at login.
        assertEquals(null, Autostart.launchPath())
        val home = java.nio.file.Files.createTempDirectory("home").toFile()
        assertFalse(Autostart.set(true, "linux", home.path))
        assertTrue(home.listFiles()!!.isEmpty())
    }

    @Test fun macAndLinuxWriteThenRemoveTheEntry() {
        val home = java.nio.file.Files.createTempDirectory("home").toFile()
        val plist = java.io.File(home, "Library/LaunchAgents/io.github.devjardins.sidespeed.companion.plist")
        assertTrue(Autostart.set(true, "mac os x", home.path, "/Apps/SideSpeed Companion"))
        assertTrue(plist.readText().contains("/Apps/SideSpeed Companion"))
        assertTrue(Autostart.set(false, "mac os x", home.path, "/Apps/SideSpeed Companion"))
        assertFalse(plist.exists())

        val desktop = java.io.File(home, ".config/autostart/sidespeed-companion.desktop")
        assertTrue(Autostart.set(true, "linux", home.path, "/opt/app"))
        assertTrue(desktop.readText().contains("Exec=\"/opt/app\""))
        assertTrue(Autostart.set(false, "linux", home.path, "/opt/app"))
        assertFalse(desktop.exists())
    }

    @Test fun disablingNeedsNoLauncher() {
        val home = java.nio.file.Files.createTempDirectory("home").toFile()
        assertTrue(Autostart.set(false, "linux", home.path, null))
    }
}
