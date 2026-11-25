package io.github.devjardins.sidespeed.companion.ui.text

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.intl.Locale
import androidx.compose.ui.unit.LayoutDirection
import io.github.devjardins.sidespeed.companion.l10n.effectiveLanguageTag
import io.github.devjardins.sidespeed.companion.l10n.formatDecimal
import io.github.devjardins.sidespeed.companion.l10n.formatInteger
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
    /** Left-to-right isolate for technical values (addresses, ports, codes, versions). */
    data class Ltr(val value: String) : UiText
    /** First-strong isolate for labels people type (TV names, network names). */
    data class Fsi(val value: String) : UiText
    /** A number with [digits] decimals, written in the current locale's digits. */
    data class Decimal(val value: Double, val digits: Int) : UiText
}

/** Numbers in sentence order, before substitution: whole numbers group-free, one-decimal fallback for raw doubles. */
private fun formatNumberArg(arg: Any, localeTag: String): Any = when (arg) {
    is Int -> formatInteger(arg.toLong(), localeTag)
    is Long -> formatInteger(arg, localeTag)
    is Double -> formatDecimal(arg, 1, localeTag)
    is UiText.Decimal -> formatDecimal(arg.value, arg.digits, localeTag)
    else -> arg
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

private fun isolated(rtl: Boolean, open: String, close: String, value: String): String =
    if (rtl) "$open$value$close" else value

/** Args that are UiText are resolved first; Ltr/Fsi are wrapped only when [rtl]. */
fun UiText.resolve(lookup: StringLookup, rtl: Boolean): String = when (this) {
    is UiText.Res -> lookup.string(id, resolveArgs(args, lookup, rtl))
    is UiText.Plural -> lookup.plural(id, count, resolveArgs(args, lookup, rtl))
    is UiText.Raw -> text
    is UiText.Ltr -> isolated(rtl, "\u2066", "\u2069", value)
    is UiText.Fsi -> isolated(rtl, "\u2068", "\u2069", value)
    is UiText.Decimal -> formatDecimal(value, digits, "en-US")
}

@Composable
private fun resolveComposableArgs(args: List<Any>, rtl: Boolean, localeTag: String): Array<Any> =
    Array(args.size) { index ->
        when (val arg = args[index]) {
            is UiText -> arg.resolve(rtl, localeTag)
            else -> formatNumberArg(arg, localeTag)
        }
    }

@Composable
private fun UiText.resolve(rtl: Boolean, localeTag: String): String = when (this) {
    is UiText.Res -> {
        val resolved = resolveComposableArgs(args, rtl, localeTag)
        if (resolved.isEmpty()) stringResource(id) else stringResource(id, *resolved)
    }
    is UiText.Plural -> {
        val resolved = resolveComposableArgs(args, rtl, localeTag)
        if (resolved.isEmpty()) pluralStringResource(id, count) else pluralStringResource(id, count, *resolved)
    }
    is UiText.Raw -> text
    is UiText.Ltr -> isolated(rtl, "\u2066", "\u2069", value)
    is UiText.Fsi -> isolated(rtl, "\u2068", "\u2069", value)
    is UiText.Decimal -> formatDecimal(value, digits, localeTag)
}

@Composable
fun UiText.resolve(): String {
    // The root provides the effective direction; numbers follow the effective tag,
    // so a requested language that never shipped still formats in ASCII English.
    val requested = Locale.current.toLanguageTag()
    return resolve(LocalLayoutDirection.current == LayoutDirection.Rtl, effectiveLanguageTag(requested))
}

private suspend fun resolveSuspendArgs(args: List<Any>, rtl: Boolean, localeTag: String): Array<Any> =
    Array(args.size) { index ->
        when (val arg = args[index]) {
            is UiText -> arg.resolveSuspend(rtl, localeTag)
            else -> formatNumberArg(arg, localeTag)
        }
    }

private suspend fun UiText.resolveSuspend(rtl: Boolean, localeTag: String): String = when (this) {
    is UiText.Res -> {
        val resolved = resolveSuspendArgs(args, rtl, localeTag)
        if (resolved.isEmpty()) getString(id) else getString(id, *resolved)
    }
    is UiText.Plural -> {
        val resolved = resolveSuspendArgs(args, rtl, localeTag)
        if (resolved.isEmpty()) getPluralString(id, count) else getPluralString(id, count, *resolved)
    }
    is UiText.Raw -> text
    is UiText.Ltr -> isolated(rtl, "\u2066", "\u2069", value)
    is UiText.Fsi -> isolated(rtl, "\u2068", "\u2069", value)
    is UiText.Decimal -> formatDecimal(value, digits, localeTag)
}

suspend fun UiText.resolveSuspend(
    rtl: Boolean = layoutDirectionForTag(effectiveLanguageTag()) == LayoutDirection.Rtl,
): String = resolveSuspend(rtl, effectiveLanguageTag())
