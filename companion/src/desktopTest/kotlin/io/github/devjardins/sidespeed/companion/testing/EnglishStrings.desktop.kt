package io.github.devjardins.sidespeed.companion.testing

import io.github.devjardins.sidespeed.companion.ui.text.StringLookup
import org.jetbrains.compose.resources.PluralStringResource
import org.jetbrains.compose.resources.StringResource
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

actual object EnglishStrings : StringLookup {
    internal data class LoadedResources(
        val strings: Map<String, String>,
        val plurals: Map<String, Map<String, String>>,
    )

    private val placeholderRegex = Regex("""%(\d+)\$[ds]""")
    private val loaded: LoadedResources by lazy { parseStringsXml(stringsXmlFile()) }

    internal fun stringsXmlFile(): File {
        val direct = File("src/commonMain/composeResources/values/strings.xml")
        if (direct.isFile) return direct
        val fromRoot = File("companion/src/commonMain/composeResources/values/strings.xml")
        if (fromRoot.isFile) return fromRoot
        error("Could not find src/commonMain/composeResources/values/strings.xml from ${File(".").absolutePath}")
    }

    internal fun allResources(): LoadedResources = loaded

    override fun string(id: StringResource, args: Array<Any>): String {
        val template = loaded.strings[id.key]
            ?: error("String '${id.key}' not found in composeResources/values/strings.xml")
        return formatComposeTemplate(template, args)
    }

    override fun plural(id: PluralStringResource, count: Int, args: Array<Any>): String {
        val quantities = loaded.plurals[id.key]
            ?: error("Plurals '${id.key}' not found in composeResources/values/strings.xml")
        val template = if (count == 1) {
            quantities["one"] ?: quantities["other"]
        } else {
            quantities["other"]
        } ?: error("Missing quantity for plural '${id.key}' (count=$count)")
        return formatComposeTemplate(template, args)
    }

    private fun formatComposeTemplate(template: String, args: Array<Any>): String {
        if (args.isEmpty()) return template
        return placeholderRegex.replace(template) { match ->
            val index = match.groupValues[1].toInt() - 1
            args[index].toString()
        }
    }

    private fun parseStringsXml(file: File): LoadedResources {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val root = doc.documentElement
        val strings = LinkedHashMap<String, String>()
        val plurals = LinkedHashMap<String, Map<String, String>>()

        val children = root.childNodes
        for (i in 0 until children.length) {
            val node = children.item(i) as? Element ?: continue
            when (node.tagName) {
                "string" -> {
                    val name = node.getAttribute("name")
                    if (name.isNotEmpty()) {
                        strings[name] = unescapeComposeString(node.textContent)
                    }
                }
                "plurals" -> {
                    val name = node.getAttribute("name")
                    if (name.isNotEmpty()) {
                        val items = LinkedHashMap<String, String>()
                        val itemNodes = node.childNodes
                        for (j in 0 until itemNodes.length) {
                            val item = itemNodes.item(j) as? Element ?: continue
                            if (item.tagName == "item") {
                                val quantity = item.getAttribute("quantity")
                                if (quantity.isNotEmpty()) {
                                    items[quantity] = unescapeComposeString(item.textContent)
                                }
                            }
                        }
                        plurals[name] = items
                    }
                }
            }
        }
        return LoadedResources(strings = strings, plurals = plurals)
    }
}

/** Matches Compose Multiplatform `handleSpecialCharacters` (\uXXXX, \n, \t, \\). */
internal fun unescapeComposeString(raw: String): String {
    val sb = StringBuilder(raw.length)
    var i = 0
    while (i < raw.length) {
        val c = raw[i]
        if (c == '\\' && i + 1 < raw.length) {
            when (raw[i + 1]) {
                '\\' -> {
                    sb.append('\\')
                    i += 2
                    continue
                }
                'n' -> {
                    sb.append('\n')
                    i += 2
                    continue
                }
                't' -> {
                    sb.append('\t')
                    i += 2
                    continue
                }
                'u' if i + 5 < raw.length -> {
                    val hex = raw.substring(i + 2, i + 6)
                    if (hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
                        sb.append(hex.toInt(16).toChar())
                        i += 6
                        continue
                    }
                }
            }
        }
        sb.append(c)
        i++
    }
    return sb.toString()
}
