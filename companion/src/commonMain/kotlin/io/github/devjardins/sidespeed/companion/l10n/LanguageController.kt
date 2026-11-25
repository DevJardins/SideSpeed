package io.github.devjardins.sidespeed.companion.l10n

import kotlinx.coroutines.flow.StateFlow

/**
 * Reads and applies the Companion's language choice. The Language page (Task 9) builds on this;
 * each platform supplies its own storage.
 */
interface LanguageController {
    /** The chosen BCP 47 tag, or null for the device language. */
    val chosen: StateFlow<String?>
    /** The languages the picker offers. */
    val shipped: List<String>
    /** True when choosing needs an app restart to take effect. */
    val needsRestart: Boolean
    /** Applies [tag], or puts the device language back when [tag] is null. */
    fun choose(tag: String?)
}
