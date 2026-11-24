package io.github.devjardins.sidespeed.ui.text

import io.github.devjardins.sidespeed.R
import io.github.devjardins.sidespeed.testing.english
import io.github.devjardins.sidespeed.testing.unescapeAndroidString
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class UiTextTest {
    private val fake = object : StringLookup {
        private val strings = mapOf(
            1 to "%1\$s then %2\$d",
            2 to "inner %1\$s",
            3 to "outer %1\$s",
        )
        private val plurals = mapOf(
            10 to ("%1\$d app" to "%1\$d apps"),
        )

        override fun string(id: Int, args: Array<Any>): String {
            val template = strings.getValue(id)
            return if (args.isEmpty()) template else String.format(Locale.US, template, *args)
        }

        override fun plural(id: Int, count: Int, args: Array<Any>): String {
            val (one, other) = plurals.getValue(id)
            val template = if (count == 1) one else other
            return if (args.isEmpty()) template else String.format(Locale.US, template, *args)
        }
    }

    @Test
    fun res_formats_positional_args() {
        assertEquals(
            "a then 3",
            UiText.Res(1, listOf("a", 3)).resolve(fake, rtl = false),
        )
    }

    @Test
    fun nested_uitext_args_resolve_first() {
        val nested = UiText.Res(3, listOf(UiText.Res(2, listOf("val"))))
        assertEquals("outer inner val", nested.resolve(fake, rtl = false))
    }

    @Test
    fun ltr_unwrapped_in_ltr_layout() {
        assertEquals(
            "192.168.4.24",
            UiText.Ltr("192.168.4.24").resolve(fake, rtl = false),
        )
    }

    @Test
    fun ltr_isolated_in_rtl_layout() {
        assertEquals(
            "\u2066192.168.4.24\u2069",
            UiText.Ltr("192.168.4.24").resolve(fake, rtl = true),
        )
    }

    @Test
    fun raw_is_unchanged() {
        assertEquals(
            "exit 1",
            UiText.Raw("exit 1").resolve(fake, rtl = true),
        )
    }

    @Test
    fun plural_resolves_count_and_nested_args() {
        assertEquals(
            "1 app",
            UiText.Plural(10, 1, listOf(1)).resolve(fake, rtl = false),
        )
        assertEquals(
            "5 apps",
            UiText.Plural(10, 5, listOf(5)).resolve(fake, rtl = false),
        )
    }

    @Test
    fun uiText_vararg_builds_res() {
        assertEquals(
            "a then 3",
            uiText(1, "a", 3).resolve(fake, rtl = false),
        )
    }

    @Test
    fun english_helper_reads_strings_xml() {
        assertEquals("SideSpeed", UiText.Res(R.string.app_name).english())
    }

    @Test
    fun android_string_unescaping_handles_escapes_and_quotes() {
        assertEquals("It's \"fine\"\n@?\\", unescapeAndroidString("It\\'s \\\"fine\\\"\\n\\@\\?\\\\"))
        assertEquals("  padded  ", unescapeAndroidString("  \"  padded  \"  "))
        assertEquals("a\\nb", unescapeAndroidString("a\\\\nb"))
        assertEquals("Line one line two", unescapeAndroidString("\n    Line one\n    line two\n"))
    }
}
