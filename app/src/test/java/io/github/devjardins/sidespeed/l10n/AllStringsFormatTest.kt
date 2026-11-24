package io.github.devjardins.sidespeed.l10n

import io.github.devjardins.sidespeed.testing.EnglishStrings
import org.junit.Assert.assertFalse
import org.junit.Test
import java.util.Locale

class AllStringsFormatTest {
    private val placeholderRegex = Regex("""%(\d+)\$([sd])""")

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
