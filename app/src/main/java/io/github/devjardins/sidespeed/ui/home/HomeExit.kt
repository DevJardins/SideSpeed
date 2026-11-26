package io.github.devjardins.sidespeed.ui.home

import android.content.Intent

/** Pure parameters of the system Home exit intent (plain-JVM testable). */
data class HomeExitIntentSpec(val action: String, val category: String, val flags: Int)

/** System Home intent parameters: main entry, Home category, new task. */
fun homeExitIntentSpec(): HomeExitIntentSpec = HomeExitIntentSpec(
    action = Intent.ACTION_MAIN,
    category = Intent.CATEGORY_HOME,
    flags = Intent.FLAG_ACTIVITY_NEW_TASK,
)

/** System Home intent: opens the device's home screen on a new task. */
fun homeExitIntent(): Intent {
    val spec = homeExitIntentSpec()
    return Intent(spec.action).apply {
        addCategory(spec.category)
        flags = spec.flags
    }
}

/**
 * Leaves SideSpeed for the system Home screen, always finishing afterwards.
 * The start is best-effort (a missing Home must not trap the user here).
 */
fun exitToSystemHome(startHome: () -> Unit, finish: () -> Unit) {
    runCatching { startHome() }
    finish()
}
