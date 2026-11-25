package io.github.devjardins.sidespeed.l10n

import io.github.devjardins.sidespeed.testing.EnglishStrings
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

internal object EnglishSnapshotSupport {
    private const val HEADER =
        "# English snapshot: key<TAB>text; plurals as key#one, key#other. " +
            "Values use strings.xml raw text (not runtime-unescaped). " +
            "Backslashes, TAB, and newline in values are written as \\\\, \\t, and \\n."

    fun snapshotFile(): File {
        val direct = File("src/test/resources/l10n/english-snapshot.tsv")
        if (File("src/main/res/values/strings.xml").isFile) return direct
        val fromRoot = File("app/src/test/resources/l10n/english-snapshot.tsv")
        if (File("app/src/main/res/values/strings.xml").isFile) return fromRoot
        error("Could not find english-snapshot.tsv for strings.xml from ${File(".").absolutePath}")
    }

    fun updateSnapshotRequested(): Boolean =
        System.getProperty("sidespeed.updateSnapshot") == "true"

    fun entriesFromStringsXml(): Map<String, String> = parseStringsXml(EnglishStrings.stringsXmlFile())

    fun readSnapshot(file: File): Map<String, String> {
        if (!file.isFile) return emptyMap()
        val out = linkedMapOf<String, String>()
        file.forEachLine { line ->
            if (line.isEmpty() || line.startsWith('#')) return@forEachLine
            val tab = line.indexOf('\t')
            require(tab > 0) { "Invalid snapshot line (expected key<TAB>text): $line" }
            val key = line.substring(0, tab)
            val value = unescapeTsvValue(line.substring(tab + 1))
            out[key] = value
        }
        return out
    }

    fun writeSnapshot(file: File, entries: Map<String, String>) {
        file.parentFile.mkdirs()
        val body = entries.keys.sorted().joinToString("\n") { key ->
            "$key\t${escapeTsvValue(entries.getValue(key))}"
        }
        file.writeText("$HEADER\n$body\n")
    }

    private fun parseStringsXml(file: File): Map<String, String> {
        val factory = DocumentBuilderFactory.newInstance().apply {
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            isXIncludeAware = false
            isExpandEntityReferences = false
        }
        val doc = factory.newDocumentBuilder().parse(file)
        val root = doc.documentElement
        val out = linkedMapOf<String, String>()
        val children = root.childNodes
        for (i in 0 until children.length) {
            val node = children.item(i)
            if (node.nodeType != Node.ELEMENT_NODE) continue
            val element = node as Element
            if (element.getAttribute("translatable") == "false") continue
            val name = element.getAttribute("name")
            if (name.isEmpty()) continue
            when (element.tagName) {
                "string" -> out[name] = element.textContent.orEmpty()
                "plurals" -> {
                    val itemNodes = element.childNodes
                    for (j in 0 until itemNodes.length) {
                        val item = itemNodes.item(j)
                        if (item.nodeType != Node.ELEMENT_NODE || item.nodeName != "item") continue
                        val quantity = (item as Element).getAttribute("quantity")
                        if (quantity.isEmpty()) continue
                        out["$name#$quantity"] = item.textContent.orEmpty()
                    }
                }
            }
        }
        return out
    }

    private fun escapeTsvValue(value: String): String =
        buildString(value.length) {
            value.forEach { c ->
                when (c) {
                    '\\' -> append("\\\\")
                    '\t' -> append("\\t")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    else -> append(c)
                }
            }
        }

    private fun unescapeTsvValue(value: String): String {
        val sb = StringBuilder(value.length)
        var i = 0
        while (i < value.length) {
            if (value[i] == '\\' && i + 1 < value.length) {
                when (value[i + 1]) {
                    't' -> {
                        sb.append('\t')
                        i += 2
                        continue
                    }
                    'n' -> {
                        sb.append('\n')
                        i += 2
                        continue
                    }
                    'r' -> {
                        sb.append('\r')
                        i += 2
                        continue
                    }
                    '\\' -> {
                        sb.append('\\')
                        i += 2
                        continue
                    }
                }
            }
            sb.append(value[i])
            i++
        }
        return sb.toString()
    }
}