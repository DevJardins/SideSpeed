package io.github.devjardins.sidespeed.companion.l10n

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NoHardCodedTextTest {
    private val sentenceLiteral = Regex(""""[A-Z][A-Za-z']*(?: [^"]+)+"""")
    private val valuePrefixedLiteral = Regex(""""(?:\$\{[^}]+\}|\$[A-Za-z_][A-Za-z0-9_]*|\d+)(?: [A-Za-z][^"]+)+"""")
    private val anyQuotedLiteral = Regex(""""[^"\n]*"""")
    private val whenBranchLiteral = Regex("""->\s*"([^"]+)"""")
    private val valInitLiteral = Regex("""\b(?:val|var)\s+\w+\s*=\s*"([^"]+)"""")
    private val capitalizedWord = Regex(""""[A-Z][a-z']*"""")
    private val lowercasePhrase = Regex(""""[a-z][a-z']*(?:\s+[^"]+)+"""")
    private val tripleQuotedLiteral = Regex("\"\"\"[^\"]*[A-Za-z][^\"]*\"\"\"")
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
        inUiCall: Boolean = false,
    ): List<String> {
        val trimmed = line.trimStart()
        if (trimmed.startsWith("//") || trimmed.startsWith("/*") || trimmed.startsWith("*")) {
            return emptyList()
        }
        if (ignoredLineTokens.any { line.contains(it) }) return emptyList()
        if (trimmed.startsWith("import ")) return emptyList()

        val effectiveInUiCall = inUiCall || lineHasUiMarker(line)
        val hits = LinkedHashSet<String>()
        fun recordIfNotAllowed(literal: String, shapeNeedsTechnicalCheck: Boolean = false) {
            val unquoted = literal.removeSurrounding("\"").removeSurrounding("\"\"\"")
            if (unquoted.isEmpty() || !hasLetters.containsMatchIn(unquoted)) return
            if (shapeNeedsTechnicalCheck && isTechnicalLiteral(unquoted, line)) return
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
        for (match in whenBranchLiteral.findAll(line)) {
            recordIfNotAllowed("\"${match.groupValues[1]}\"")
        }
        for (match in valInitLiteral.findAll(line)) {
            recordIfNotAllowed("\"${match.groupValues[1]}\"", shapeNeedsTechnicalCheck = true)
        }
        for (match in capitalizedWord.findAll(line)) {
            val literal = match.value
            val body = literal.removeSurrounding("\"")
            if (!body.contains(' ') && body.length > 1) {
                recordIfNotAllowed(literal, shapeNeedsTechnicalCheck = true)
            }
        }
        for (match in lowercasePhrase.findAll(line)) {
            recordIfNotAllowed(match.value, shapeNeedsTechnicalCheck = true)
        }
        for (match in tripleQuotedLiteral.findAll(line)) {
            recordIfNotAllowed(match.value, shapeNeedsTechnicalCheck = true)
        }
        if (effectiveInUiCall) {
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

    internal fun netParensOutsideStrings(line: String): Int {
        var net = 0
        var i = 0
        var inQuote = false
        var inRaw = false
        while (i < line.length) {
            when {
                !inQuote && line.startsWith("\"\"\"", i) -> {
                    inRaw = !inRaw
                    i += 3
                }
                !inRaw && line[i] == '"' -> {
                    val escaped = i > 0 && line[i - 1] == '\\'
                    if (!escaped) inQuote = !inQuote
                    i++
                }
                !inQuote && !inRaw -> {
                    when (line[i]) {
                        '(' -> net++
                        ')' -> net--
                    }
                    i++
                }
                else -> i++
            }
        }
        return net
    }

    internal fun lineHasUiMarker(line: String): Boolean = uiCallMarkers.any { line.contains(it) }

    internal fun rawStringLineHits(line: String, inRaw: Boolean, relPath: String, allowlist: Set<String>): Pair<Boolean, List<String>> {
        var inside = inRaw
        val hits = mutableListOf<String>()
        val inner = StringBuilder()
        var i = 0
        while (i < line.length) {
            if (line.startsWith("\"\"\"", i)) {
                if (inside) {
                    val chunk = inner.toString()
                    inner.clear()
                    if (hasLetters.containsMatchIn(chunk) &&
                        !isAllowlisted(relPath, chunk, allowlist) &&
                        !isTechnicalRaw(chunk.trim(), line)
                    ) {
                        hits += "\"\"\"$chunk\"\"\""
                    }
                    inside = false
                } else {
                    inside = true
                }
                i += 3
                continue
            }
            if (inside) inner.append(line[i])
            i++
        }
        if (inside && inner.isNotEmpty() && hasLetters.containsMatchIn(inner.toString())) {
            val chunk = inner.toString()
            if (!isAllowlisted(relPath, chunk, allowlist) && !isTechnicalRaw(chunk.trim(), line)) {
                hits += "\"\"\"$chunk\"\"\""
            }
        }
        return inside to hits
    }

    private fun isAllowlisted(relPath: String, unquoted: String, allowlist: Set<String>): Boolean =
        "$relPath|\"$unquoted\"" in allowlist || "$relPath|$unquoted" in allowlist

    internal fun scanLines(
        lines: List<String>,
        relPath: String = "",
        allowlist: Set<String> = emptySet(),
    ): List<String> {
        val violations = mutableListOf<String>()
        var uiDepth = 0
        var inRaw = false
        lines.forEachIndexed { zeroBasedLine, line ->
            val inUiCall = uiDepth > 0 || lineHasUiMarker(line)
            for (literal in findViolations(line, relPath, allowlist, inUiCall)) {
                violations += "$relPath:${zeroBasedLine + 1}: $literal"
            }
            val (nextRaw, rawHits) = rawStringLineHits(line, inRaw, relPath, allowlist)
            inRaw = nextRaw
            for (hit in rawHits) {
                violations += "$relPath:${zeroBasedLine + 1}: $hit"
            }
            if (uiDepth == 0 && lineHasUiMarker(line)) {
                uiDepth = netParensOutsideStrings(line).coerceAtLeast(0)
            } else if (uiDepth > 0) {
                uiDepth += netParensOutsideStrings(line)
            }
            if (uiDepth <= 0) uiDepth = 0
        }
        return violations
    }

    // Raw strings are mostly prose, so only plist, desktop-entry and regex content counts as technical.
    private fun isTechnicalRaw(unquoted: String, line: String): Boolean {
        if (line.contains("Regex(")) return true
        if (unquoted.contains("<?xml") || unquoted.contains("<!DOCTYPE") || unquoted.contains("<plist")) return true
        if (unquoted.startsWith("<") && unquoted.endsWith(">")) return true
        if (unquoted.contains("Desktop Entry") || unquoted.contains('=')) return true
        if (unquoted.contains("\\b(") || unquoted.contains("\\d{") || unquoted.contains("(?<!") || unquoted.contains("\\S")) return true
        return false
    }

    private fun isTechnicalLiteral(unquoted: String, line: String): Boolean {
        if (line.contains("Regex(")) return true
        if (line.trimStart().startsWith("const val")) return true
        if (unquoted.contains('.') || unquoted.contains('/') || unquoted.contains('\\')) return true
        if (unquoted.contains('$') || unquoted.contains("::") || unquoted.contains("&&")) return true
        if (unquoted.contains("http") || unquoted.contains("getprop") || unquoted.contains("content query")) return true
        if (unquoted.contains(Regex("""(?i)(script|sha-?\d|tls\d|unicode|adb-|\.pem|\.json|\.dmg|\.msi|\.deb|\.rpm|\.apk)"""))) return true
        if (unquoted.endsWith("-") || unquoted.startsWith("activity-")) return true
        if (line.contains("setOf(") && unquoted.length <= 5) return true
        if (unquoted.contains("<?xml") || unquoted.contains("<!DOCTYPE") || unquoted.contains("<plist")) return true
        if (unquoted.contains("<dict>") || unquoted.contains("</plist>")) return true
        if (unquoted.contains("Desktop Entry") || unquoted.startsWith("Type=") || unquoted.startsWith("Name=")) return true
        if (unquoted.contains("X-GNOME")) return true
        if (unquoted.contains("ProgramArguments") || unquoted.contains("RunAtLoad")) return true
        if (unquoted.contains("\\b(") || unquoted.contains("\\d{") || unquoted.contains("(?<!")) return true
        if (unquoted.contains(" at (\\S+)")) return true
        return false
    }

    @Test
    fun findViolations_pins_guard_regex_and_exclusions() {
        assertEquals(listOf("\"Trust this network\""), findViolations("""SsButton("Trust this network", onTrust)"""))
        assertEquals(listOf("\"Cancel\""), findViolations("""SsButton("Cancel", onCancel)"""))
        assertEquals(listOf("\"Offline\""), findViolations("""TvStatus.OFFLINE -> "Offline","""))
        assertEquals(listOf("\"just now\""), findViolations("""val hint = "just now""""))
        assertEquals(listOf("\"debug tag\""), findViolations("""private val tag = "debug tag""""))
        assertEquals(
            listOf("\"Add\""),
            findViolations("""    "Add",""", inUiCall = true),
        )
        assertEquals(
            listOf("\"15 minutes\"", "\"1 hour\""),
            findViolations("""SsSegmented(listOf("15 minutes", "1 hour"), 0) {}"""),
        )
        assertEquals(
            listOf("\"${'$'}{a.queue} more waiting\""),
            findViolations("""SsText("${'$'}{a.queue} more waiting", Ss.caption)"""),
        )
        assertEquals(
            listOf("\"Pair steps go here\"", "\"\"\"Pair steps go here\"\"\""),
            findViolations("val steps = \"\"\"Pair steps go here\"\"\""),
        )
        assertEquals(listOf("\"offline\""), findViolations("""SsText("offline", Ss.caption)"""))
        assertEquals(
            listOf("example/Foo.kt:2: \"\"\"Pair steps go here\"\"\""),
            scanLines(
                listOf(
                    "val steps = \"\"\"",
                    "Pair steps go here",
                    "\"\"\".trimIndent()",
                ),
                "example/Foo.kt",
            ),
        )
        assertEquals(
            listOf("\"Save (draft)\"", "\"leaked\""),
            scanLines(
                listOf(
                    """SsButton("Save (draft)", onSave)""",
                    """val tag = "leaked"""",
                ),
            ).map { it.substringAfterLast(':').trim() },
        )
        assertEquals(
            listOf("\"Save (draft)\""),
            scanLines(
                listOf(
                    """SsButton("Save (draft)", onSave)""",
                    """    "plain",""",
                ),
            ).map { it.substringAfterLast(':').trim() },
        )
        assertEquals(
            listOf("example/Foo.kt:2: \"\"\"Open Settings. Then tap Save.\"\"\""),
            scanLines(
                listOf("val steps = \"\"\"", "Open Settings. Then tap Save.", "\"\"\".trimIndent()"),
                "example/Foo.kt",
            ),
        )
        assertEquals(0, netParensOutsideStrings("""SsButton("Save (draft)", onSave)"""))
        assertEquals(0, netParensOutsideStrings("""SsText("ok", Ss.body)"""))

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
                    violations += scanLines(file.readLines(), relPath, allowlist)
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