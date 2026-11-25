package io.github.devjardins.sidespeed.l10n

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import java.util.Locale

interface LanguageStore {
    fun chosen(): String?
    fun setChosen(tag: String?)
}

class PrefsLanguageStore(context: Context) : LanguageStore {
    private val prefs = context.getSharedPreferences("sidespeed_language", Context.MODE_PRIVATE)

    override fun chosen(): String? = prefs.getString("tag", null)

    override fun setChosen(tag: String?) {
        prefs.edit().putString("tag", tag).commit()
    }
}

object AppLanguage {
    fun chosen(context: Context): String? {
        if (Build.VERSION.SDK_INT >= 33) {
            val locales = context.getSystemService(LocaleManager::class.java)?.applicationLocales
            if (locales == null || locales.isEmpty) return null
            return locales.toLanguageTags()
        }
        return PrefsLanguageStore(context).chosen()
    }

    fun choose(activity: Activity, tag: String?) {
        if (Build.VERSION.SDK_INT >= 33) {
            activity.getSystemService(LocaleManager::class.java)
                ?.setApplicationLocales(LocaleList.forLanguageTags(tag ?: ""))
            return
        }
        PrefsLanguageStore(activity).setChosen(tag)
        activity.recreate()
    }

    fun wrap(base: Context): Context {
        if (Build.VERSION.SDK_INT >= 33) return base
        val tag = PrefsLanguageStore(base).chosen() ?: return base
        val locale = localeFor(tag)
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        config.setLayoutDirection(locale)
        return base.createConfigurationContext(config)
    }

    fun localeFor(tag: String): Locale = Locale.forLanguageTag(tag)
}
