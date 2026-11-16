package io.github.devjardins.sidespeed.companion.desktop

import java.io.File

/** "Start when I log in", per OS. Off by default. [launchPath] is the packaged app's executable. */
object Autostart {
    const val APP_ID = "io.github.devjardins.sidespeed.companion"

    /** The packaged launcher, set by jpackage; null when run from Gradle or an IDE (autostart then does nothing). */
    fun launchPath(): String? = System.getProperty("jpackage.app-path")?.takeIf { it.isNotBlank() }

    fun macPlist(path: String): String = """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>Label</key><string>$APP_ID</string>
    <key>ProgramArguments</key><array><string>${xml(path)}</string></array>
    <key>RunAtLoad</key><true/>
</dict>
</plist>
"""

    fun linuxDesktopEntry(path: String): String = """[Desktop Entry]
Type=Application
Name=SideSpeed Companion
Exec="${path.replace("\\", "\\\\").replace("\"", "\\\"")}"
X-GNOME-Autostart-enabled=true
"""

    /** Value of the HKCU Run entry. */
    fun windowsRunValue(path: String): String = "\"$path\""

    private fun xml(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    /** Returns whether the setting now matches [enabled]. */
    fun set(enabled: Boolean, os: String = System.getProperty("os.name").orEmpty().lowercase(), home: String = System.getProperty("user.home")): Boolean {
        val path = launchPath()
        if (enabled && path == null) return false
        return runCatching {
            when {
                os.startsWith("mac") -> file(File(home, "Library/LaunchAgents/$APP_ID.plist"), enabled) { macPlist(path!!) }
                os.startsWith("windows") -> windows(enabled, path)
                else -> file(File(home, ".config/autostart/sidespeed-companion.desktop"), enabled) { linuxDesktopEntry(path!!) }
            }
            true
        }.getOrDefault(false)
    }

    private fun file(f: File, enabled: Boolean, content: () -> String) {
        if (enabled) {
            f.parentFile.mkdirs()
            f.writeText(content())
        } else {
            f.delete()
        }
    }

    private fun windows(enabled: Boolean, path: String?) {
        val key = "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run"
        val cmd = if (enabled) listOf("reg", "add", key, "/v", "SideSpeedCompanion", "/t", "REG_SZ", "/d", windowsRunValue(path!!), "/f")
        else listOf("reg", "delete", key, "/v", "SideSpeedCompanion", "/f")
        ProcessBuilder(cmd).redirectErrorStream(true).start().also { it.outputStream.close() }.waitFor()
    }
}
