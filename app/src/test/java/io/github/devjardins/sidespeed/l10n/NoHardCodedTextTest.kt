package io.github.devjardins.sidespeed.l10n

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class NoHardCodedTextTest {
    private val sentenceLiteral = Regex(""""[A-Z][A-Za-z']*(?: [^"]+)+"""")
    private val ignoredLineTokens = listOf("Log.", "HelperLog", "error(", "require(", "check(")

    internal fun findViolations(
        line: String,
        relPath: String = "",
        allowlist: Set<String> = emptySet(),
    ): List<String> {
        val trimmed = line.trimStart()
        if (trimmed.startsWith("//") || trimmed.startsWith("/*") || trimmed.startsWith("*")) {
            return emptyList()
        }
        if (ignoredLineTokens.any { line.contains(it) }) return emptyList()

        val hits = mutableListOf<String>()
        for (match in sentenceLiteral.findAll(line)) {
            val literal = match.value
            val unquoted = literal.removeSurrounding("\"")
            if ("$relPath|$literal" !in allowlist && "$relPath|$unquoted" !in allowlist) {
                hits += literal
            }
        }
        return hits
    }

    @Test
    fun findViolations_pins_guard_regex_and_exclusions() {
        assertEquals(listOf("\"Back to home\""), findViolations("""Text("Back to home")"""))
        assertEquals(listOf("\"ADB debugging must stay on\""), findViolations("""Text("ADB debugging must stay on")"""))
        assertEquals(listOf("\"SideSpeed is ready\""), findViolations("""Text("SideSpeed is ready")"""))
        assertEquals(listOf("\"TV settings\""), findViolations("""Text("TV settings")"""))
        assertEquals(
            listOf("\"Fire TV doesn't allow other home screens.\""),
            findViolations("""label = "Fire TV doesn't allow other home screens.""""),
        )

        assertEquals(emptyList<String>(), findViolations("""Log.d(TAG, "Some message here")"""))
        assertEquals(emptyList<String>(), findViolations("""Text("Settings")"""))
        assertEquals(emptyList<String>(), findViolations("""shell("pm disable-user --user 0")"""))
        assertEquals(emptyList<String>(), findViolations("""// Text("Back to home")"""))
    }

    @Test
    fun no_sentence_literals_in_kotlin() {
        val sourceRoot = sourceRoot()
        val allowlist = readList("/l10n/hardcoded-text-allowlist.txt").toSet()

        val violations = mutableListOf<String>()

        sourceRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .sortedBy { it.path }
            .forEach { file ->
                val relPath = file.relativeTo(sourceRoot).invariantSeparatorsPath
                if (relPath.startsWith("helper/")) return@forEach

                file.readLines().forEachIndexed { zeroBasedLine, line ->
                    for (literal in findViolations(line, relPath, allowlist)) {
                        violations += "$relPath:${zeroBasedLine + 1}: $literal"
                    }
                }
            }

        assertTrue(
            "Hard-coded sentence literals found (move to strings.xml or allowlist):\n${violations.joinToString("\n")}",
            violations.isEmpty(),
        )
    }

    private fun readList(resourcePath: String): List<String> {
        val stream = requireNotNull(javaClass.getResourceAsStream(resourcePath)) {
            "Missing test resource: $resourcePath"
        }
        return stream.bufferedReader().useLines { lines ->
            lines.map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .toList()
        }
    }

    private fun sourceRoot(): File {
        val direct = File("src/main/java/io/github/devjardins/sidespeed")
        if (direct.isDirectory) return direct
        val fromRoot = File("app/src/main/java/io/github/devjardins/sidespeed")
        if (fromRoot.isDirectory) return fromRoot
        error("Could not find source root from ${File(".").absolutePath}")
    }
}
