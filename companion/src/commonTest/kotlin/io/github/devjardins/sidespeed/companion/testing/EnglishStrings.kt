package io.github.devjardins.sidespeed.companion.testing

import io.github.devjardins.sidespeed.companion.ui.text.StringLookup
import io.github.devjardins.sidespeed.companion.ui.text.UiText
import io.github.devjardins.sidespeed.companion.ui.text.resolve

expect object EnglishStrings : StringLookup

fun UiText.english(): String = resolve(EnglishStrings, rtl = false)
