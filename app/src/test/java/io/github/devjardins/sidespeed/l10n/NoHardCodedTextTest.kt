package io.github.devjardins.sidespeed.l10n

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class NoHardCodedTextTest {
    private val sentenceLiteral = Regex(""""[A-Z][a-z']*(?: [^"]+)+"""")
    private val ignoredLineTokens = listOf("Log.", "HelperLog", "error(", "require(", "check(")

    @Test
    fun no_sentence_literals_in_kotlin() {
        val sourceRoot = sourceRoot()
        val notMigrated = readList("/l10n/not-migrated-yet.txt").toSet()
        val allowlist = readList("/l10n/hardcoded-text-allowlist.txt").toSet()

        val violations = mutableListOf<String>()

        sourceRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .sortedBy { it.path }
            .forEach { file ->
                val relPath = file.relativeTo(sourceRoot).invariantSeparatorsPath
                if (relPath.startsWith("helper/") || relPath in notMigrated) return@forEach

                file.readLines().forEachIndexed { zeroBasedLine, line ->
                    val trimmed = line.trimStart()
                    if (trimmed.startsWith("//") || trimmed.startsWith("/*") || trimmed.startsWith("*")) {
                        return@forEachIndexed
                    }
                    if (ignoredLineTokens.any { line.contains(it) }) return@forEachIndexed

                    for (match in sentenceLiteral.findAll(line)) {
                        val literal = match.value
                        val unquoted = literal.removeSurrounding("\"")
                        if ("$relPath|$literal" !in allowlist && "$relPath|$unquoted" !in allowlist) {
                            violations += "$relPath:${zeroBasedLine + 1}: $literal"
                        }
                    }
                }
            }

        assertTrue(
            "Hard-coded sentence literals found (move to strings.xml or allowlist):\n${violations.joinToString("\n")}",
            violations.isEmpty(),
        )
    }

    @Test
    fun not_migrated_list_only_names_existing_files() {
        val sourceRoot = sourceRoot()
        val missing = readList("/l10n/not-migrated-yet.txt")
            .filter { !File(sourceRoot, it).isFile }

        assertTrue(
            "not-migrated-yet.txt names non-existent files:\n${missing.joinToString("\n")}",
            missing.isEmpty(),
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
