package io.github.devjardins.sidespeed.companion.l10n

import io.github.devjardins.sidespeed.companion.resources.Res
import io.github.devjardins.sidespeed.companion.resources.allPluralStringResources
import io.github.devjardins.sidespeed.companion.resources.allStringResources
import io.github.devjardins.sidespeed.companion.testing.EnglishStrings
import io.github.devjardins.sidespeed.companion.testing.english
import io.github.devjardins.sidespeed.companion.ui.text.UiText
import io.github.devjardins.sidespeed.companion.ui.text.resolveSuspend
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AllStringsFormatTest {
    private val placeholderRegex = Regex("""%(\d+)\$([sd])""")
    private val forbiddenLiterals = listOf("192.168.", "3503400", "sidespeed.app", "github.com/", "SideSpeed-Companion.apk")

    @Test
    fun no_literal_addresses_codes_or_urls_in_strings_xml() {
        val resources = EnglishStrings.allResources()
        val hits = mutableListOf<String>()
        resources.strings.forEach { (name, value) ->
            forbiddenLiterals.filter { value.contains(it) }.forEach { literal ->
                hits += "string/$name contains '$literal'"
            }
        }
        resources.plurals.forEach { (name, quantities) ->
            quantities.forEach { (quantity, value) ->
                forbiddenLiterals.filter { value.contains(it) }.forEach { literal ->
                    hits += "plurals/$name[$quantity] contains '$literal'"
                }
            }
        }
        assertTrue(
            hits.isEmpty(),
            "Addresses, codes, APK filenames, and URLs must be passed as UiText.Ltr placeholders:\n${hits.joinToString("\n")}",
        )
    }

    @Test
    fun every_string_formats_with_its_placeholders() {
        val resources = EnglishStrings.allResources()
        resources.strings.forEach { (name, template) ->
            assertComposeSafe("string/$name", template)
        }
        resources.plurals.forEach { (name, quantities) ->
            quantities.forEach { (quantity, template) ->
                assertComposeSafe("plurals/$name[$quantity]", template)
            }
        }
    }

    @Test
    fun every_resource_matches_compose_runtime_in_english() = runBlocking {
        val resources = EnglishStrings.allResources()
        assertEquals(resources.strings.keys, Res.allStringResources.keys)
        assertEquals(resources.plurals.keys, Res.allPluralStringResources.keys)

        for ((name, res) in Res.allStringResources) {
            val template = resources.strings.getValue(name)
            val args = dummyArgs(template)
            val text = UiText.Res(res, args)
            assertEquals(text.english(), text.resolveSuspend(rtl = false), "string/$name")
        }
        for ((name, res) in Res.allPluralStringResources) {
            val quantities = resources.plurals.getValue(name)
            for (count in listOf(1, 2)) {
                val template = if (count == 1) quantities["one"] ?: quantities.getValue("other") else quantities.getValue("other")
                val args = dummyArgs(template)
                val text = UiText.Plural(res, count, args)
                assertEquals(text.english(), text.resolveSuspend(rtl = false), "plurals/$name[$count]")
            }
        }
    }

    private fun dummyArgs(template: String): List<Any> {
        val placeholders = placeholderRegex.findAll(template).toList()
        val maxIndex = placeholders.maxOfOrNull { it.groupValues[1].toInt() } ?: 0
        if (maxIndex == 0) return emptyList()
        val types = mutableMapOf<Int, String>()
        for (match in placeholders) {
            val idx = match.groupValues[1].toInt()
            val type = match.groupValues[2]
            val prev = types.put(idx, type)
            assertTrue(prev == null || prev == type, "Conflicting placeholder types for index $idx in '$template'")
        }
        for (idx in 1..maxIndex) {
            assertTrue(idx in types, "Non-contiguous placeholder index $idx in '$template'")
        }
        return (1..maxIndex).map { idx -> if (types[idx] == "d") idx else "arg$idx" }
    }

    private fun assertComposeSafe(label: String, template: String) {
        assertFalse(template.contains("\\'"), "$label contains \\' (Compose Multiplatform does not unescape it): '$template'")
        assertFalse(template.contains("\\\""), "$label contains \\\" (Compose Multiplatform does not unescape it): '$template'")
        assertFalse(template.contains("%%"), "$label contains %% (Compose Multiplatform does not unescape it): '$template'")
        dummyArgs(template)
        var stripped = placeholderRegex.replace(template, "")
        if (stripped.endsWith('%') && placeholderRegex.containsMatchIn(template)) {
            stripped = stripped.dropLast(1)
        }
        assertFalse(stripped.contains('%'), "$label has stray '%' outside %n\$s/%n\$d: '$template'")
    }
}
