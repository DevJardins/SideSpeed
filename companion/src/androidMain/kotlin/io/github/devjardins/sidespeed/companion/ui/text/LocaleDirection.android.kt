package io.github.devjardins.sidespeed.companion.ui.text

import android.text.TextUtils
import android.view.View
import androidx.compose.ui.unit.LayoutDirection
import java.util.Locale

actual fun isDefaultLocaleRtl(): Boolean {
    val locale = Locale.getDefault()
    return TextUtils.getLayoutDirectionFromLocale(locale) == View.LAYOUT_DIRECTION_RTL ||
        isRtlLanguageOrScript(locale.language, locale.script)
}

actual fun layoutDirectionForTag(tag: String): LayoutDirection {
    val locale = Locale.forLanguageTag(tag)
    return if (TextUtils.getLayoutDirectionFromLocale(locale) == View.LAYOUT_DIRECTION_RTL ||
        isRtlLanguageOrScript(locale.language, locale.script)
    ) {
        LayoutDirection.Rtl
    } else {
        LayoutDirection.Ltr
    }
}
