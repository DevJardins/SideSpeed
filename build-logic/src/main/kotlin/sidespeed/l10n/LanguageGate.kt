package sidespeed.l10n

import com.ibm.icu.text.PluralRules
import com.ibm.icu.util.ULocale
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

data class LanguageScore(val tag: String, val translated: Int, val total: Int) {
    val missing: Int get() = total - translated
    val share: Double get() = if (total == 0) 1.0 else translated.toDouble() / total
}

data class GateResult(val included: List<LanguageScore>, val excluded: List<LanguageScore>)

object LanguageGate {
    private val PLACEHOLDER = Regex("%([0-9]+)\\$([sd])")
    private val LANGUAGE = Regex("[a-z]{2,3}")
    private val REGION = Regex("r([A-Z]{2}|[0-9]{3})")

    /** The project ships every language at or above this share of English; also printed by [summary]. */

    /** aapt2 `-c` / localeFilters form of a BCP-47 tag: `pt-BR` -> `pt-rBR`, `sr-Latn` -> `b+sr+Latn`. */
    fun tagToFilter(tag: String): String {
        val parts = tag.split('-')
        return when {
            parts.size == 1 -> tag
            parts.any { it.length == 4 } -> "b+" + parts.joinToString("+")
            parts.size == 2 -> "${parts[0]}-r${parts[1]}"
            else -> "b+" + parts.joinToString("+")
        }
    }

    fun qualifierToTag(folderName: String): String? {
        if (!folderName.startsWith("values-")) return null
        val qualifier = folderName.removePrefix("values-")
        if (qualifier.startsWith("b+")) {
            val parts = qualifier.split("+").filter { it != "b" && it.isNotEmpty() }
            if (parts.isEmpty() || !LANGUAGE.matches(parts[0])) return null
            val rest = parts.drop(1).map {
                when {
                    it.length == 2 -> it.uppercase()
                    it.length == 4 -> it.lowercase().replaceFirstChar(Char::uppercaseChar)
                    else -> it
                }
            }
            return (listOf(parts[0]) + rest).joinToString("-")
        }
        val segments = qualifier.split("-")
        if (!LANGUAGE.matches(segments[0])) return null
        val region = segments.drop(1).firstNotNullOfOrNull { REGION.matchEntire(it)?.groupValues?.get(1) }
        return if (region != null) "${segments[0]}-$region" else segments[0]
    }

    fun evaluate(englishXml: File, resDir: File, bar: Double): GateResult {
        val english = parseStrings(englishXml)
        val total = english.size
        val included = mutableListOf<LanguageScore>()
        val excluded = mutableListOf<LanguageScore>()
        val dirs = resDir.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name }.orEmpty()
        for (dir in dirs) {
            val tag = qualifierToTag(dir.name) ?: continue
            val score = try {
                score(tag, english, parseStrings(File(dir, "strings.xml")), total)
            } catch (e: Exception) {
                // Fail closed: an unreadable or malformed translation ships nothing.
                LanguageScore(tag, 0, total)
            }
            if (score.share >= bar) included.add(score) else excluded.add(score)
        }
        return GateResult(included.sortedBy { it.tag }, excluded.sortedBy { it.tag })
    }

    fun summary(module: String, result: GateResult, bar: Double = 0.70): String {
        val barPercent = Math.round(bar * 100)
        if (result.included.isEmpty() && result.excluded.isEmpty()) {
            return "SideSpeed languages ($module): bar $barPercent%, English only"
        }
        val lines = mutableListOf("SideSpeed languages ($module): bar $barPercent%")
        for (score in result.included) lines.add("  in:  ${score.tag} ${percent(score)} (${score.missing} missing)")
        for (score in result.excluded) lines.add("  out: ${score.tag} ${percent(score)} (${score.missing} missing)")
        return lines.joinToString("\n")
    }

    private fun percent(score: LanguageScore): String =
        if (score.total == 0) "100%" else "${score.translated * 100 / score.total}%"

    private fun score(tag: String, english: Map<String, Entry>, translated: Map<String, Entry>, total: Int): LanguageScore {
        var done = 0
        for ((name, entry) in english) {
            if (isTranslated(tag, entry, translated[name])) done++
        }
        return LanguageScore(tag, done, total)
    }

    private fun isTranslated(tag: String, entry: Entry, actual: Entry?): Boolean {
        when (entry) {
            is Entry.Text -> {
                if (actual !is Entry.Text) return false
                if (actual.text.isBlank()) return false
                return actual.args == entry.args
            }
            is Entry.Group -> {
                if (actual !is Entry.Group) return false
                val required = PluralRules.forLocale(ULocale.forLanguageTag(tag)).keywords
                for (quantity in required) {
                    val got = actual.items[quantity] ?: return false
                    if (got.text.isBlank()) return false
                    val want = entry.items[quantity]
                    if (want != null && got.args != want.args) return false
                }
                return true
            }
        }
    }

    private fun placeholders(text: String): Set<Pair<Int, Char>> =
        PLACEHOLDER.findAll(text).map { it.groupValues[1].toInt() to it.groupValues[2].single() }.toSet()

    private sealed interface Entry {
        data class Text(val text: String, val args: Set<Pair<Int, Char>>) : Entry
        data class Group(val items: Map<String, Text>) : Entry
    }

    private fun parseStrings(file: File): Map<String, Entry> {
        val factory = DocumentBuilderFactory.newInstance().apply {
            // Translation files come from outside contributors: never resolve DOCTYPEs or entities.
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            isXIncludeAware = false
            isExpandEntityReferences = false
        }
        val builder = factory.newDocumentBuilder().apply { setErrorHandler(null) }
        val root = file.inputStream().use { builder.parse(it) }.documentElement
        val entries = linkedMapOf<String, Entry>()
        val nodes = root.childNodes
        for (i in 0 until nodes.length) {
            val node = nodes.item(i)
            if (node.nodeType != Node.ELEMENT_NODE) continue
            val element = node as Element
            if (element.getAttribute("translatable") == "false") continue
            val name = element.getAttribute("name")
            if (name.isEmpty()) continue
            when (node.nodeName) {
                "string" -> {
                    val text = node.textContent.orEmpty()
                    entries[name] = Entry.Text(text, placeholders(text))
                }
                "plurals" -> {
                    val items = linkedMapOf<String, Entry.Text>()
                    val kids = node.childNodes
                    for (j in 0 until kids.length) {
                        val kid = kids.item(j)
                        if (kid.nodeType != Node.ELEMENT_NODE || kid.nodeName != "item") continue
                        val quantity = (kid as Element).getAttribute("quantity")
                        if (quantity.isEmpty()) continue
                        val text = kid.textContent.orEmpty()
                        items[quantity] = Entry.Text(text, placeholders(text))
                    }
                    entries[name] = Entry.Group(items)
                }
                else -> continue
            }
        }
        return entries
    }
}
