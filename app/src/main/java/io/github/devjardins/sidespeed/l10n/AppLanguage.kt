package io.github.devjardins.sidespeed.l10n

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.LocaleList
import io.github.devjardins.sidespeed.BuildConfig
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
            return locales[0].toLanguageTag()
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
        val tag = PrefsLanguageStore(base).chosen()
        if (tag == null) {
            // "Same as device" after another choice in this process: put the device language back.
            Locale.setDefault(Resources.getSystem().configuration.locales[0])
            return base
        }
        val locale = localeFor(tag)
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        config.setLayoutDirection(locale)
        return base.createConfigurationContext(config)
    }

    fun localeFor(tag: String): Locale = Locale.forLanguageTag(tag)

    /** The tags the picker offers: shipped translations, plus the debug pseudolocales. */
    val shipped: List<String>
        get() = if (BuildConfig.DEBUG) ShippedLanguages.TAGS + listOf("en-XA", "ar-XB") else ShippedLanguages.TAGS

    /**
     * The locale the app actually shows for [requestedTag]: the matched shipped language,
     * else US English. Numbers and layout direction follow this, never the raw request.
     */
    fun effectiveLocale(requestedTag: String, shipped: List<String>): Locale =
        matchShipped(requestedTag, shipped)?.let(::localeFor) ?: Locale.US

    /** The locale the app actually shows now, from the configuration's requested tag. */
    fun effectiveLocale(context: Context): Locale =
        effectiveLocale(context.resources.configuration.locales[0].toLanguageTag(), shipped)
}
