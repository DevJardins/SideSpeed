package io.github.devjardins.sidespeed.l10n

import io.github.devjardins.sidespeed.testing.EnglishStrings
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class AllStringsFormatTest {
    private val placeholderRegex = Regex("""%(\d+)\$([sd])""")
    private val forbiddenLiterals = listOf("3503400", "sidespeed.app", "github.com/")

    @Test
    fun no_literal_codes_or_urls_in_strings_xml() {
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
            "Codes and URLs must be passed as UiText.Ltr placeholders, not embedded in strings.xml:\n${hits.joinToString("\n")}",
            hits.isEmpty(),
        )
    }

    @Test
    fun every_string_formats_with_its_placeholders() {
        val resources = EnglishStrings.allResources()
        resources.strings.forEach { (name, template) ->
            assertFormatsCleanly("string/$name", template)
        }
        resources.plurals.forEach { (name, quantities) ->
            quantities.forEach { (quantity, template) ->
                assertFormatsCleanly("plurals/$name[$quantity]", template)
            }
        }
    }

    private fun assertFormatsCleanly(label: String, template: String) {
        val stripped = template.replace("%%", "")
        val placeholders = placeholderRegex.findAll(stripped).toList()
        val maxIndex = placeholders.maxOfOrNull { it.groupValues[1].toInt() } ?: 0
        val args = Array<Any>(maxIndex) { "x" }
        for (match in placeholders) {
            val index = match.groupValues[1].toInt() - 1
            val type = match.groupValues[2]
            args[index] = if (type == "d") 1 else "x"
        }

        val formatted = if (args.isEmpty()) {
            String.format(Locale.US, template)
        } else {
            String.format(Locale.US, template, *args)
        }
        val formattedStripped = if (args.isEmpty()) {
            String.format(Locale.US, stripped)
        } else {
            String.format(Locale.US, stripped, *args)
        }

        assertFalse(
            "$label has unescaped '%' after formatting: '$formatted'",
            formattedStripped.contains('%'),
        )
    }
}
