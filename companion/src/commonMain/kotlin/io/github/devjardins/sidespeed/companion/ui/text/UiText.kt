package io.github.devjardins.sidespeed.companion.ui.text

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import org.jetbrains.compose.resources.PluralStringResource
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getPluralString
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

sealed interface UiText {
    data class Res(val id: StringResource, val args: List<Any> = emptyList()) : UiText
    data class Plural(val id: PluralStringResource, val count: Int, val args: List<Any> = emptyList()) : UiText
    data class Raw(val text: String) : UiText
    data class Ltr(val value: String) : UiText
}

fun uiText(id: StringResource, vararg args: Any): UiText = UiText.Res(id, args.toList())

fun uiPlural(id: PluralStringResource, count: Int, vararg args: Any): UiText = UiText.Plural(id, count, args.toList())

interface StringLookup {
    fun string(id: StringResource, args: Array<Any>): String
    fun plural(id: PluralStringResource, count: Int, args: Array<Any>): String
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

@Composable
private fun resolveComposableArgs(args: List<Any>, rtl: Boolean): Array<Any> =
    Array(args.size) { index ->
        when (val arg = args[index]) {
            is UiText -> arg.resolve(rtl)
            else -> arg
        }
    }

@Composable
private fun UiText.resolve(rtl: Boolean): String = when (this) {
    is UiText.Res -> {
        val resolved = resolveComposableArgs(args, rtl)
        if (resolved.isEmpty()) stringResource(id) else stringResource(id, *resolved)
    }
    is UiText.Plural -> {
        val resolved = resolveComposableArgs(args, rtl)
        if (resolved.isEmpty()) pluralStringResource(id, count) else pluralStringResource(id, count, *resolved)
    }
    is UiText.Raw -> text
    is UiText.Ltr -> if (rtl) "\u2066$value\u2069" else value
}

@Composable
fun UiText.resolve(): String = resolve(LocalLayoutDirection.current == LayoutDirection.Rtl)

private suspend fun resolveSuspendArgs(args: List<Any>, rtl: Boolean): Array<Any> =
    Array(args.size) { index ->
        when (val arg = args[index]) {
            is UiText -> arg.resolveSuspend(rtl)
            else -> arg
        }
    }

suspend fun UiText.resolveSuspend(rtl: Boolean = isDefaultLocaleRtl()): String = when (this) {
    is UiText.Res -> {
        val resolved = resolveSuspendArgs(args, rtl)
        if (resolved.isEmpty()) getString(id) else getString(id, *resolved)
    }
    is UiText.Plural -> {
        val resolved = resolveSuspendArgs(args, rtl)
        if (resolved.isEmpty()) getPluralString(id, count) else getPluralString(id, count, *resolved)
    }
    is UiText.Raw -> text
    is UiText.Ltr -> if (rtl) "\u2066$value\u2069" else value
}
