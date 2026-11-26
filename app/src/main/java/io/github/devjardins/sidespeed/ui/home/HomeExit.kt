package io.github.devjardins.sidespeed.ui.home

import android.content.Intent

/** System Home intent: opens the device's home screen on a new task. */
fun homeExitIntent(): Intent = Intent(Intent.ACTION_MAIN).apply {
    addCategory(Intent.CATEGORY_HOME)
    flags = Intent.FLAG_ACTIVITY_NEW_TASK
}

/**
 * Leaves SideSpeed for the system Home screen, always finishing afterwards.
 * The start is best-effort (a missing Home must not trap the user here).
 */
fun exitToSystemHome(startHome: () -> Unit, finish: () -> Unit) {
    runCatching { startHome() }
    finish()
}
