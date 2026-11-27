package io.github.devjardins.sidespeed.helper

/** The only Android packages SideSpeed's helper and launch commands recognise. */
object SideSpeedPackages {
    const val RELEASE = "io.github.devjardins.sidespeed"
    const val DEBUG = "io.github.devjardins.sidespeed.debug"
    const val MAIN_ACTIVITY = "io.github.devjardins.sidespeed.MainActivity"
    val ALL: Set<String> = setOf(RELEASE, DEBUG)
}
