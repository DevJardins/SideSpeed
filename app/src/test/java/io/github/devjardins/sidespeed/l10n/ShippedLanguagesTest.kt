package io.github.devjardins.sidespeed.l10n

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * The TV app ships only languages at or above the 70% gate. The Gradle task
 * `generateShippedLanguages` evaluates the gate and writes both this object's
 * source and the expected tags below, so this test pins the two together.
 */
class ShippedLanguagesTest {
    @Test
    fun shipped_languages_match_the_gate() {
        val expected = expectedTagsFile().readLines().map { it.trim() }.filter { it.isNotEmpty() }
        assertEquals(expected, ShippedLanguages.TAGS)
    }

    private fun expectedTagsFile(): File {
        val direct = File("build/generated/l10n/expectedTags.txt")
        if (direct.isFile) return direct
        val fromRoot = File("app/build/generated/l10n/expectedTags.txt")
        if (fromRoot.isFile) return fromRoot
        error("Could not find build/generated/l10n/expectedTags.txt from ${File(".").absolutePath}")
    }
}
