package io.github.devjardins.sidespeed.companion.tv

import java.io.File

/** Where the desktop companion keeps its files, following each OS's convention for per-user app data. */
object DesktopDirs {
    /** The app-data directory for this user. */
    fun appData(): File = appData(System.getProperty("os.name").orEmpty(), System.getProperty("user.home"), System::getenv)

    /** The ADB key directory, passed to [KadbTvConnector]. */
    fun keyDir(): File = File(appData(), "adb")

    internal fun appData(osName: String, home: String, env: (String) -> String?): File {
        val os = osName.lowercase()
        return when {
            os.startsWith("mac") || os.startsWith("darwin") ->
                File(home, "Library/Application Support/SideSpeed Companion")
            os.startsWith("windows") ->
                File(env("APPDATA")?.takeIf { it.isNotBlank() } ?: "$home\\AppData\\Roaming", "SideSpeed Companion")
            else ->
                File(env("XDG_DATA_HOME")?.takeIf { it.isNotBlank() } ?: "$home/.local/share", "sidespeed-companion")
        }
    }
}
