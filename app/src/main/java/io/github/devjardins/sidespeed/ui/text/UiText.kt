package io.github.devjardins.sidespeed.ui.text

import android.content.Context
import android.view.View
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection

sealed interface UiText {
    data class Res(@StringRes val id: Int, val args: List<Any> = emptyList()) : UiText
    data class Plural(@PluralsRes val id: Int, val count: Int, val args: List<Any> = emptyList()) : UiText
    data class Raw(val text: String) : UiText
    data class Ltr(val value: String) : UiText
}

fun uiText(@StringRes id: Int, vararg args: Any): UiText = UiText.Res(id, args.toList())

fun stringLookup(context: Context): StringLookup = ContextStringLookup(context)

interface StringLookup {
    fun string(@StringRes id: Int, args: Array<Any>): String
    fun plural(@PluralsRes id: Int, count: Int, args: Array<Any>): String
}

private class ContextStringLookup(private val context: Context) : StringLookup {
    override fun string(@StringRes id: Int, args: Array<Any>): String =
        if (args.isEmpty()) context.getString(id) else context.getString(id, *args)

    override fun plural(@PluralsRes id: Int, count: Int, args: Array<Any>): String =
        if (args.isEmpty()) {
            context.resources.getQuantityString(id, count)
        } else {
            context.resources.getQuantityString(id, count, *args)
        }
}

private fun resolveArgs(args: List<Any>, lookup: StringLookup, rtl: Boolean): Array<Any> =
    Array(args.size) { index ->
        when (val arg = args[index]) {
            is UiText -> arg.resolve(lookup, rtl)
            else -> arg
        }
    }

/** Args that are UiText are resolved first; Ltr is wrapped in U+2066 LRI … U+2069 PDI only when [rtl]. */
fun UiText.resolve(lookup: StringLookup, rtl: Boolean): String = when (this) {
    is UiText.Res -> lookup.string(id, resolveArgs(args, lookup, rtl))
    is UiText.Plural -> lookup.plural(id, count, resolveArgs(args, lookup, rtl))
    is UiText.Raw -> text
    is UiText.Ltr -> if (rtl) "\u2066$value\u2069" else value
}

fun UiText.resolve(context: Context): String {
    val rtl = context.resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL
    return resolve(ContextStringLookup(context), rtl)
}

@Composable
fun UiText.resolve(): String {
    val context = LocalContext.current
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    return resolve(ContextStringLookup(context), rtl)
}
