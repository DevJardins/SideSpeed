package io.github.devjardins.sidespeed.companion.tv

import io.github.devjardins.sidespeed.helper.HelperLaunch

/**
 * The only shell commands the companion ever sends to a TV. The TV trusts the companion's key, so nothing
 * else is built from outside input; the single parameter is a pid, which must be positive.
 */
object TvCommands {
    const val SERIAL = "getprop ro.serialno"
    const val PKG_PATH = "pm path io.github.devjardins.sidespeed"
    const val PIDOF = "pidof sidespeed_helper"

    /** `pid` is an Int, so it can only ever be digits; zero and negatives are refused. */
    fun cmdline(pid: Int): String {
        require(pid > 0) { "pid must be positive" }
        return "cat /proc/$pid/cmdline"
    }

    val KILL: String = HelperLaunch.KILL_COMMAND
    val LAUNCH: String = HelperLaunch.command()

    // Add/scan only.
    const val MANUFACTURER = "getprop ro.product.manufacturer"
    const val MODEL = "getprop ro.product.model"
    const val OS_VERSION = "getprop ro.build.version.name"
}
