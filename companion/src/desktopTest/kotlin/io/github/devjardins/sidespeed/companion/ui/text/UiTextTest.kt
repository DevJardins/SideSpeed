package io.github.devjardins.sidespeed.companion.ui.text

import androidx.compose.ui.unit.LayoutDirection
import io.github.devjardins.sidespeed.companion.resources.Res
import io.github.devjardins.sidespeed.companion.resources.about_check_failed
import io.github.devjardins.sidespeed.companion.resources.about_downloading
import io.github.devjardins.sidespeed.companion.resources.home_checked_min_ago
import io.github.devjardins.sidespeed.companion.testing.english
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.PluralStringResource
import org.jetbrains.compose.resources.StringResource
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals

class UiTextTest {
    private val placeholderRegex = Regex("""%(\d+)\$[ds]""")

    private val fake = object : StringLookup {
        private val templates = mapOf(
            "pair" to "%1\$s then %2\$d",
            "outer" to "Outer: %1\$s",
            "inner" to "Inner %1\$s",
        )
        private val plurals = mapOf(
            "items" to mapOf("one" to "%1\$d item", "other" to "%1\$d items"),
        )

        override fun string(id: StringResource, args: Array<Any>): String {
            val t = templates[id.key] ?: error("Missing ${id.key}")
            return placeholderRegex.replace(t) { m -> args[m.groupValues[1].toInt() - 1].toString() }
        }

        override fun plural(id: PluralStringResource, count: Int, args: Array<Any>): String {
            val q = plurals[id.key] ?: error("Missing ${id.key}")
            val t = if (count == 1) q.getValue("one") else q.getValue("other")
            return placeholderRegex.replace(t) { m -> args[m.groupValues[1].toInt() - 1].toString() }
        }
    }

    @Test
    fun res_formats_positional_args() {
        val text = UiText.Res(Res.string.about_downloading, listOf(42))
        assertEquals("Downloading… 42%", text.english())
    }

    @Test
    fun nested_uitext_args_resolve_first() {
        val inner = UiText.Res(Res.string.about_check_failed)
        val outer = UiText.Res(Res.string.about_downloading, listOf(99))
        assertEquals("Couldn't reach GitHub. Try again later.", inner.english())
        assertEquals("Downloading… 99%", outer.english())
        assertEquals(
            "Downloading… 50%",
            runBlocking { UiText.Res(Res.string.about_downloading, listOf(50)).resolveSuspend(rtl = false) },
        )
        assertEquals(
            "Couldn't reach GitHub. Try again later.",
            runBlocking { inner.resolveSuspend(rtl = false) },
        )
        assertEquals(
            "Checked 1 min ago",
            runBlocking { UiText.Plural(Res.plurals.home_checked_min_ago, 1, listOf(1)).resolveSuspend(rtl = false) },
        )
        assertEquals(
            "Checked 5 min ago",
            runBlocking { UiText.Plural(Res.plurals.home_checked_min_ago, 5, listOf(5)).resolveSuspend(rtl = false) },
        )
    }

    @Test
    fun ltr_unwrapped_in_ltr_layout() {
        assertEquals("192.168.4.24", UiText.Ltr("192.168.4.24").resolve(fake, rtl = false))
    }

    @Test
    fun ltr_isolated_in_rtl_layout() {
        assertEquals("\u2066192.168.4.24\u2069", UiText.Ltr("192.168.4.24").resolve(fake, rtl = true))
    }

    @Test
    fun fsi_unwrapped_in_ltr_layout() {
        assertEquals("Living room", UiText.Fsi("Living room").resolve(fake, rtl = false))
    }

    @Test
    fun fsi_isolated_in_rtl_layout() {
        assertEquals("\u2068Living room\u2069", UiText.Fsi("Living room").resolve(fake, rtl = true))
    }

    @Test
    fun raw_is_unchanged() {
        assertEquals("exit 1", UiText.Raw("exit 1").resolve(fake, rtl = true))
    }

    @Test
    fun layoutDirectionForLocale_handles_ltr_and_rtl_scripts() {
        listOf("en", "en-US", "fr", "ar-Latn").forEach { tag ->
            assertEquals(LayoutDirection.Ltr, layoutDirectionForLocale(Locale.forLanguageTag(tag)), tag)
        }
        listOf(
            "ar", "ar-EG", "ar-XB", "he", "iw", "fa", "ur", "yi",
            "ps", "sd", "ug", "dv", "ckb", "ku-Arab",
        ).forEach { tag ->
            assertEquals(LayoutDirection.Rtl, layoutDirectionForLocale(Locale.forLanguageTag(tag)), tag)
        }
    }
}
