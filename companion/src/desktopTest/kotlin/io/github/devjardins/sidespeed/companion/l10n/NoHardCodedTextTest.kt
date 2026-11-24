package io.github.devjardins.sidespeed.companion.l10n

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NoHardCodedTextTest {
    private val sentenceLiteral = Regex(""""[A-Z][A-Za-z']*(?: [^"]+)+"""")
    private val valuePrefixedLiteral = Regex(""""(?:\$\{[^}]+\}|\$[A-Za-z_][A-Za-z0-9_]*|\d+)(?: [A-Za-z][^"]+)+"""")
    private val anyQuotedLiteral = Regex(""""[^"\n]*"""")
    private val hasLetters = Regex("""[A-Za-z]""")
    private val ignoredLineTokens = listOf("Log.", "error(", "require(", "check(")
    private val uiCallMarkers = listOf(
        "SsText(", "SsButton(", "SsIconButton(", "SsCheckRow(", "SsSwitchRow(", "SsSegmented(",
        "Header(", "Pill(", "Item(", "Notification(", "setContentText(",
        "contentDescription =", "tooltip =", "title =",
    )

    internal fun findViolations(
        line: String,
        relPath: String = "",
        allowlist: Set<String> = emptySet(),
        previousLine: String = "",
    ): List<String> {
        val trimmed = line.trimStart()
        if (trimmed.startsWith("//") || trimmed.startsWith("/*") || trimmed.startsWith("*")) {
            return emptyList()
        }
        if (ignoredLineTokens.any { line.contains(it) }) return emptyList()

        val hits = LinkedHashSet<String>()
        fun recordIfNotAllowed(literal: String) {
            val unquoted = literal.removeSurrounding("\"")
            if ("$relPath|$literal" !in allowlist && "$relPath|$unquoted" !in allowlist) {
                hits += literal
            }
        }

        for (match in sentenceLiteral.findAll(line)) {
            recordIfNotAllowed(match.value)
        }
        for (match in valuePrefixedLiteral.findAll(line)) {
            recordIfNotAllowed(match.value)
        }

        val inUiCall = uiCallMarkers.any { line.contains(it) } ||
            (previousLine.trimEnd().endsWith("(") && uiCallMarkers.any { previousLine.contains(it) })
        if (inUiCall) {
            for (match in anyQuotedLiteral.findAll(line)) {
                val literal = match.value
                val unquoted = literal.removeSurrounding("\"")
                if (hasLetters.containsMatchIn(unquoted)) {
                    recordIfNotAllowed(literal)
                }
            }
        }
        return hits.toList()
    }

    @Test
    fun findViolations_pins_guard_regex_and_exclusions() {
        assertEquals(listOf("\"Trust this network\""), findViolations("""SsButton("Trust this network", onTrust)"""))
        assertEquals(listOf("\"Cancel\""), findViolations("""SsButton("Cancel", onCancel)"""))
        assertEquals(
            listOf("\"Add\""),
            findViolations("""    "Add",""", previousLine = """SsButton("""),
        )
        assertEquals(
            listOf("\"15 minutes\"", "\"1 hour\""),
            findViolations("""SsSegmented(listOf("15 minutes", "1 hour"), 0) {}"""),
        )
        assertEquals(
            listOf("\"${'$'}{a.queue} more waiting\""),
            findViolations("""SsText("${'$'}{a.queue} more waiting", Ss.caption)"""),
        )

        assertEquals(emptyList(), findViolations("""Log.w(TAG, "Some message here")"""))
        assertEquals(emptyList(), findViolations("""SsText("✓", Ss.body)"""))
        assertEquals(emptyList(), findViolations("""SsText("!", Ss.caption)"""))
        assertEquals(emptyList(), findViolations("""SsTextField(port, { port = it }, "37123")"""))
        assertEquals(emptyList(), findViolations("""AnimatedContent(state, label = "screen")"""))
        assertEquals(emptyList(), findViolations("""// SsButton("Cancel", onCancel)"""))
    }

    @Test
    fun no_hard_coded_text_in_companion_kotlin() {
        val root = repoRoot()
        val allowlist = readList("/l10n/hardcoded-text-allowlist.txt").toSet()
        val sourceRoots = listOf(
            "companion/src/commonMain/kotlin",
            "companion/src/desktopMain/kotlin",
            "companion/src/androidMain/kotlin",
            "companion-desktop/src/main/kotlin",
            "companion-android/src/main/kotlin",
        )

        val violations = mutableListOf<String>()
        for (dirPath in sourceRoots) {
            val dir = File(root, dirPath)
            require(dir.isDirectory) { "Missing source root: ${dir.absolutePath}" }
            dir.walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .sortedBy { it.path }
                .forEach { file ->
                    val relPath = file.relativeTo(root).invariantSeparatorsPath

                    var prevLine = ""
                    file.readLines().forEachIndexed { zeroBasedLine, line ->
                        for (literal in findViolations(line, relPath, allowlist, prevLine)) {
                            violations += "$relPath:${zeroBasedLine + 1}: $literal"
                        }
                        if (line.isNotBlank()) prevLine = line
                    }
                }
        }

        assertTrue(
            violations.isEmpty(),
            "Hard-coded text found (move to strings.xml or allowlist):\n${violations.joinToString("\n")}",
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

    private fun repoRoot(): File {
        val direct = File(".")
        if (File(direct, "companion/src/commonMain/kotlin").isDirectory) return direct
        val parent = File("..")
        if (File(parent, "companion/src/commonMain/kotlin").isDirectory) return parent
        error("Could not find repository root from ${direct.absolutePath}")
    }
}
