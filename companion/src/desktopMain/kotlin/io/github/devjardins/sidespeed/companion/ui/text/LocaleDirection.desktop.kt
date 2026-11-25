package io.github.devjardins.sidespeed.companion.ui.text

import androidx.compose.ui.unit.LayoutDirection
import java.awt.ComponentOrientation
import java.util.Locale

fun layoutDirectionForLocale(locale: Locale): LayoutDirection {
    val ltr = ComponentOrientation.getOrientation(locale).isLeftToRight
    return if (isRtlLanguageOrScript(locale.language, locale.script, ltr)) {
        LayoutDirection.Rtl
    } else {
        LayoutDirection.Ltr
    }
}

actual fun isDefaultLocaleRtl(): Boolean = layoutDirectionForLocale(Locale.getDefault()) == LayoutDirection.Rtl

actual fun layoutDirectionForTag(tag: String): LayoutDirection =
    layoutDirectionForLocale(Locale.forLanguageTag(tag))
