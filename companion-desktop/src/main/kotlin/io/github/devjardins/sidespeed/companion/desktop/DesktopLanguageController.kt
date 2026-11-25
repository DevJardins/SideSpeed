package io.github.devjardins.sidespeed.companion.desktop

import io.github.devjardins.sidespeed.companion.l10n.LanguageController
import io.github.devjardins.sidespeed.companion.l10n.ShippedLanguages
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * Applies the language choice on computers by setting the JVM default locale. The default
 * locale is process-wide, so Main keys the tray and window content on [epoch]: every choice
 * recomposes them with the newly resolved resources and layout direction.
 */
class DesktopLanguageController(
    private val systemLocale: Locale,
    initial: String?,
    private val save: (String?) -> Unit,
) : LanguageController {
    private val _chosen = MutableStateFlow(initial)
    override val chosen: StateFlow<String?> = _chosen.asStateFlow()

    override val shipped: List<String> = ShippedLanguages.TAGS

    override val needsRestart: Boolean = false

    private val _epoch = MutableStateFlow(0)

    /** Bumps on every choice; Main keys the tray and window content on it. */
    val epoch: StateFlow<Int> = _epoch.asStateFlow()

    override fun choose(tag: String?) {
        Locale.setDefault(if (tag == null) systemLocale else Locale.forLanguageTag(tag))
        save(tag)
        _chosen.value = tag
        _epoch.value += 1
    }
}
