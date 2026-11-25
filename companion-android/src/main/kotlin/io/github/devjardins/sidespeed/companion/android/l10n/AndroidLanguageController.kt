package io.github.devjardins.sidespeed.companion.android.l10n

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.LocaleList
import io.github.devjardins.sidespeed.companion.android.BuildConfig
import io.github.devjardins.sidespeed.companion.l10n.LanguageController
import io.github.devjardins.sidespeed.companion.l10n.ShippedLanguages
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * Applies the language choice on phones. Android 13 and up keeps it in the system's per-app
 * language storage; below that it lives in SharedPreferences and the Activity is recreated,
 * mirroring the TV's AppLanguage split. The choice is never written to CompanionSettings:
 * LocaleManager and preferences are the single source of truth on this platform.
 */
class AndroidLanguageController(private val activity: Activity) : LanguageController {
    private val _chosen = MutableStateFlow(AndroidLanguage.chosen(activity))
    override val chosen: StateFlow<String?> = _chosen.asStateFlow()

    override val shipped: List<String> =
        ShippedLanguages.TAGS + if (BuildConfig.DEBUG) listOf("en-XA", "ar-XB") else emptyList()

    override val needsRestart: Boolean = false

    override fun choose(tag: String?) {
        AndroidLanguage.choose(activity, tag)
        _chosen.value = tag
    }
}

object AndroidLanguage {
    fun chosen(context: Context): String? {
        if (Build.VERSION.SDK_INT >= 33) {
            val locales = context.getSystemService(LocaleManager::class.java)?.applicationLocales
            if (locales == null || locales.isEmpty) return null
            return locales[0].toLanguageTag()
        }
        return LanguagePrefs(context).get()
    }

    fun choose(activity: Activity, tag: String?) {
        if (Build.VERSION.SDK_INT >= 33) {
            activity.getSystemService(LocaleManager::class.java)
                ?.setApplicationLocales(LocaleList.forLanguageTags(tag ?: ""))
            return
        }
        LanguagePrefs(activity).set(tag)
        activity.recreate()
    }

    fun wrap(base: Context): Context {
        if (Build.VERSION.SDK_INT >= 33) return base
        val tag = LanguagePrefs(base).get()
        if (tag == null) {
            // Same as the device language after another choice in this process.
            Locale.setDefault(Resources.getSystem().configuration.locales[0])
            return base
        }
        val locale = Locale.forLanguageTag(tag)
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        config.setLayoutDirection(locale)
        return base.createConfigurationContext(config)
    }
}

private class LanguagePrefs(context: Context) {
    private val prefs = context.getSharedPreferences("companion_language", Context.MODE_PRIVATE)

    fun get(): String? = prefs.getString("tag", null)

    fun set(tag: String?) {
        prefs.edit().putString("tag", tag).commit()
    }
}
