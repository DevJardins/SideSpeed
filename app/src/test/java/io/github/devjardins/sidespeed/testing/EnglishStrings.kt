package io.github.devjardins.sidespeed.testing

import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import io.github.devjardins.sidespeed.R
import io.github.devjardins.sidespeed.ui.text.StringLookup
import io.github.devjardins.sidespeed.ui.text.UiText
import io.github.devjardins.sidespeed.ui.text.resolve
import org.w3c.dom.Element
import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

object EnglishStrings : StringLookup {
    internal data class LoadedResources(
        val strings: Map<String, String>,
        val plurals: Map<String, Map<String, String>>,
    )

    private val loaded: LoadedResources by lazy { parseStringsXml(stringsXmlFile()) }

    private val stringNamesById: Map<Int, String> by lazy {
        buildIdMap(R.string::class.java)
    }

    private val pluralNamesById: Map<Int, String> by lazy {
        val pluralsClass = R::class.java.declaredClasses.firstOrNull { it.simpleName == "plurals" }
            ?: return@lazy emptyMap()
        buildIdMap(pluralsClass)
    }

    internal fun stringsXmlFile(): File {
        val direct = File("src/main/res/values/strings.xml")
        if (direct.isFile) return direct
        val fromRoot = File("app/src/main/res/values/strings.xml")
        if (fromRoot.isFile) return fromRoot
        error("Could not find src/main/res/values/strings.xml from ${File(".").absolutePath}")
    }

    internal fun allResources(): LoadedResources = loaded

    override fun string(@StringRes id: Int, args: Array<Any>): String {
        val name = stringNamesById[id]
            ?: error("Unknown @StringRes id: $id")
        val template = loaded.strings[name]
            ?: error("String '$name' not found in strings.xml")
        return if (args.isEmpty()) template else String.format(Locale.US, template, *args)
    }

    override fun plural(@PluralsRes id: Int, count: Int, args: Array<Any>): String {
        val name = pluralNamesById[id]
            ?: error("Unknown @PluralsRes id: $id")
        val quantities = loaded.plurals[name]
            ?: error("Plurals '$name' not found in strings.xml")
        val template = if (count == 1) {
            quantities["one"] ?: quantities["other"]
        } else {
            quantities["other"]
        } ?: error("Missing quantity for plural '$name' (count=$count)")
        return if (args.isEmpty()) template else String.format(Locale.US, template, *args)
    }

    private fun buildIdMap(rClass: Class<*>): Map<Int, String> =
        rClass.fields
            .filter { it.type == Int::class.javaPrimitiveType }
            .associate { it.getInt(null) to it.name }

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
                        strings[name] = unescapeAndroidString(node.textContent)
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
                                    items[quantity] = unescapeAndroidString(item.textContent)
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

internal fun unescapeAndroidString(raw: String): String {
    val sb = StringBuilder(raw.length)
    var inQuotes = false
    var pendingSpace = false
    var seenContent = false
    var i = 0
    while (i < raw.length) {
        val c = raw[i]
        if (c == '\\' && i + 1 < raw.length) {
            if (pendingSpace && seenContent) {
                sb.append(' ')
                pendingSpace = false
            }
            when (val next = raw[i + 1]) {
                'n' -> sb.append('\n')
                't' -> sb.append('\t')
                '\'' -> sb.append('\'')
                '"' -> sb.append('"')
                '\\' -> sb.append('\\')
                '@' -> sb.append('@')
                '?' -> sb.append('?')
                'u' -> {
                    require(i + 5 < raw.length) { "Incomplete unicode escape in '$raw' at index $i" }
                    val hex = raw.substring(i + 2, i + 6)
                    require(hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
                        "Invalid unicode escape '\\u$hex' in '$raw'"
                    }
                    sb.append(hex.toInt(16).toChar())
                    seenContent = true
                    i += 6
                    continue
                }
                else -> sb.append(next)
            }
            seenContent = true
            i += 2
            continue
        }
        if (c == '"') {
            if (pendingSpace && seenContent && !inQuotes) {
                sb.append(' ')
                pendingSpace = false
            }
            inQuotes = !inQuotes
            seenContent = true
            i++
            continue
        }
        if (!inQuotes && (c == ' ' || c == '\t' || c == '\n' || c == '\r')) {
            if (seenContent) pendingSpace = true
            i++
            continue
        }
        if (pendingSpace) {
            sb.append(' ')
            pendingSpace = false
        }
        sb.append(c)
        seenContent = true
        i++
    }
    return sb.toString()
}

fun UiText.english(): String = resolve(EnglishStrings, rtl = false)
