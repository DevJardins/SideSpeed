package sidespeed.l10n

import com.ibm.icu.text.PluralRules
import com.ibm.icu.util.ULocale
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Checks translated `strings.xml` files against their English source.
 *
 * Every error names the file, the key and what is wrong, in plain English.
 * See `TranslationCheckTest` for the contract and `docs/TRANSLATING.md` for
 * the volunteer-facing version of these rules.
 */
object TranslationCheck {

    /**
     * How the strings are read at run time.
     *
     * [ANDROID]: aapt2 needs \' and \" escaped, and a literal percent sign is written %%.
     * [COMPOSE]: Compose resources only unescape \n, \t, \uXXXX and \\, and only replace
     * %1$s-style placeholders, so \' shows its backslash and %% shows two percent signs.
     */
    enum class Format { ANDROID, COMPOSE }

    data class Module(
        val name: String,
        val englishFile: File,
        val resDir: File,
        val format: Format = Format.ANDROID,
    )

    data class Error(val file: String, val key: String, val message: String) {
        override fun toString(): String = "$file: $key: $message"
    }

    data class Result(val errors: List<Error>, val markdown: String)

    private val PLACEHOLDER = Regex("%([0-9]+)\\$([sd])")

    /** Android lets these plural forms leave the number placeholder out. */
    private val EXEMPT_QUANTITIES = setOf("one", "zero", "two")

    /** AGP pseudo-locales: generated, never committed, never checked. */
    private val PSEUDO_TAGS = setOf("en-XA", "ar-XB")

    fun check(
        modules: List<Module>,
        bar: Double = 0.70,
        baseRef: String? = null,
        changedKeys: Map<String, Set<String>> = emptyMap(),
        displayRoot: File? = null,
    ): Result {
        val errors = mutableListOf<Error>()
        val markdown = StringBuilder("# Translation summary\n")
        if (baseRef != null) {
            markdown.append("Base: `$baseRef`. Bar: ${barPercent(bar)}.\n")
        } else {
            markdown.append("Bar: ${barPercent(bar)}.\n")
        }
        for (module in modules) {
            markdown.append("\n## ${module.name}\n")
            val english = try {
                parseEnglish(module.englishFile.readText())
            } catch (e: Exception) {
                errors.add(Error(display(displayRoot, module.englishFile), "(file)", "This file is not valid XML, so it was skipped."))
                markdown.append("The English file is not valid XML, so this module was skipped.\n")
                continue
            }
            val translationFiles = translationFiles(module.resDir)
            for (file in translationFiles) {
                errors.addAll(checkFile(file, english, module.format, displayRoot))
            }
            val gate = LanguageGate.evaluate(module.englishFile, module.resDir, bar)
            val scores = (gate.included.map { it to true } + gate.excluded.map { it to false })
                .filter { (score, _) -> score.tag !in PSEUDO_TAGS }
                .sortedBy { (score, _) -> score.tag }
            if (scores.isEmpty()) {
                markdown.append("No translations yet.\n")
                continue
            }
            for ((score, inBar) in scores) {
                val share = score.translated * 100 / score.total.coerceAtLeast(1)
                val standing = if (inBar) {
                    "in (at or above the ${barPercent(bar)} bar)"
                } else {
                    "out (below the ${barPercent(bar)} bar)"
                }
                markdown.append("### ${score.tag} \u2014 $share% translated, $standing\n")
                if (baseRef != null) {
                    val changed = changedKeys[translationFile(module.resDir, score.tag)?.path]
                        .orEmpty()
                        .sorted()
                    val count = if (changed.size == 1) "1 string changed" else "${changed.size} strings changed"
                    markdown.append("- $count since `$baseRef`.\n")
                    val safety = changed.filter { it in english.safetyKeys }
                    if (safety.isNotEmpty()) {
                        markdown.append("- Safety strings changed: ${safety.joinToString(", ")}.\n")
                    }
                }
            }
        }
        return Result(errors, markdown.toString().trimEnd() + "\n")
    }

    /**
     * Returns the keys whose text differs between two versions of one file.
     * A missing base means the file is new, so every key counts as changed.
     * A plural group counts as one string.
     */
    fun changedKeys(baseXml: String?, currentXml: String): Set<String> {
        val current = try {
            parseLight(currentXml)
        } catch (e: Exception) {
            return emptySet()
        }
        if (baseXml == null) return current.keys
        val base = try {
            parseLight(baseXml)
        } catch (e: Exception) {
            return current.keys
        }
        val changed = mutableSetOf<String>()
        for ((key, text) in current) {
            if (base[key] != text) changed.add(key)
        }
        for (key in base.keys) {
            if (key !in current) changed.add(key)
        }
        return changed
    }

    // Parsing

    private data class RichText(val text: String, val args: List<Pair<Int, Char>>)

    private data class EnglishEntry(
        val text: RichText?,
        val items: Map<String, RichText>,
        val keeps: List<String>,
        val safety: Boolean,
    )

    private data class EnglishFile(
        val entries: Map<String, EnglishEntry>,
        val untranslatable: Set<String>,
        val safetyKeys: Set<String>,
    )

    private sealed interface TransEntry {
        data class Text(val text: RichText) : TransEntry
        data class Group(val items: Map<String, RichText>) : TransEntry
    }

    private fun document(xml: String) =
        DocumentBuilderFactory.newInstance().apply {
            // Translation files come from outside contributors: never resolve DOCTYPEs or entities.
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            isXIncludeAware = false
            isExpandEntityReferences = false
        }.newDocumentBuilder().apply { setErrorHandler(null) }
            .parse(xml.byteInputStream()).documentElement

    private fun commentsFor(children: List<Node>, index: Int): List<String> {
        val comments = mutableListOf<String>()
        var i = index - 1
        while (i >= 0) {
            val node = children[i]
            when (node.nodeType) {
                Node.COMMENT_NODE -> comments.add(0, node.nodeValue.orEmpty())
                Node.TEXT_NODE -> if (node.nodeValue.isNotBlank()) break
                else -> break
            }
            i--
        }
        return comments
    }

    private fun parseEnglish(xml: String): EnglishFile {
        val root = document(xml)
        val children = (0 until root.childNodes.length).map { root.childNodes.item(it) }
        val entries = linkedMapOf<String, EnglishEntry>()
        val untranslatable = mutableSetOf<String>()
        children.forEachIndexed { index, node ->
            if (node.nodeType != Node.ELEMENT_NODE) return@forEachIndexed
            val element = node as Element
            val name = element.getAttribute("name")
            if (name.isEmpty()) return@forEachIndexed
            if (element.getAttribute("translatable") == "false") {
                untranslatable.add(name)
                return@forEachIndexed
            }
            val comments = commentsFor(children, index)
            val keeps = keepsIn(comments)
            val safety = comments.any { it.trim().startsWith("Safety warning:") }
            when (node.nodeName) {
                "string" -> {
                    val text = element.textContent.orEmpty()
                    entries[name] = EnglishEntry(RichText(text, placeholders(text)), emptyMap(), keeps, safety)
                }
                "plurals" -> {
                    entries[name] = EnglishEntry(null, pluralItems(element), keeps, safety)
                }
                else -> Unit
            }
        }
        return EnglishFile(entries, untranslatable, entries.filter { it.value.safety }.keys)
    }

    private fun parseTranslation(xml: String): Map<String, TransEntry> {
        val root = document(xml)
        val entries = linkedMapOf<String, TransEntry>()
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
                    val text = element.textContent.orEmpty()
                    entries[name] = TransEntry.Text(RichText(text, placeholders(text)))
                }
                "plurals" -> entries[name] = TransEntry.Group(pluralItems(element))
                else -> Unit
            }
        }
        return entries
    }

    private fun pluralItems(element: Element): Map<String, RichText> {
        val items = linkedMapOf<String, RichText>()
        val kids = element.childNodes
        for (j in 0 until kids.length) {
            val kid = kids.item(j)
            if (kid.nodeType != Node.ELEMENT_NODE || kid.nodeName != "item") continue
            val quantity = (kid as Element).getAttribute("quantity")
            if (quantity.isEmpty()) continue
            val text = kid.textContent.orEmpty()
            items[quantity] = RichText(text, placeholders(text))
        }
        return items
    }

    private fun parseLight(xml: String): Map<String, String> {
        val root = document(xml)
        val entries = linkedMapOf<String, String>()
        val nodes = root.childNodes
        for (i in 0 until nodes.length) {
            val node = nodes.item(i)
            if (node.nodeType != Node.ELEMENT_NODE) continue
            val element = node as Element
            val name = element.getAttribute("name")
            if (name.isEmpty()) continue
            entries[name] = when (node.nodeName) {
                "string" -> element.textContent.orEmpty()
                "plurals" -> pluralItems(element).toSortedMap().entries.joinToString("\n") { "${it.key}=${it.value.text}" }
                else -> continue
            }
        }
        return entries
    }

    private fun keepsIn(comments: List<String>): List<String> {
        val keeps = mutableListOf<String>()
        for (comment in comments) {
            val match = Regex("Keep:\\s*(.*)", RegexOption.DOT_MATCHES_ALL).find(comment) ?: continue
            var raw = match.groupValues[1].trim().split(". ").first()
            if (raw.endsWith(".")) raw = raw.dropLast(1)
            raw.split(",").map { it.trim().trim('"') }.filter { it.isNotEmpty() }.forEach { keeps.add(it) }
        }
        return keeps
    }

    private fun placeholders(text: String): List<Pair<Int, Char>> =
        PLACEHOLDER.findAll(text).map { it.groupValues[1].toInt() to it.groupValues[2].single() }
            .sortedWith(compareBy({ it.first }, { it.second })).toList()

    // File discovery

    /** Translation files under [resDir], skipping pseudo-locales. */
    fun translationFiles(resDir: File): List<File> =
        resDir.listFiles()
            ?.filter { it.isDirectory }
            ?.sortedBy { it.name }
            ?.mapNotNull { dir ->
                if (!dir.name.startsWith("values-")) return@mapNotNull null
                val tag = try {
                    LanguageGate.qualifierToTag(dir.name)
                } catch (e: Exception) {
                    return@mapNotNull null
                } ?: return@mapNotNull null
                if (tag in PSEUDO_TAGS) return@mapNotNull null
                val file = File(dir, "strings.xml")
                if (file.isFile) file else null
            }.orEmpty()

    private fun translationFile(resDir: File, tag: String): File? =
        translationFiles(resDir).firstOrNull {
            LanguageGate.qualifierToTag(it.parentFile.name) == tag
        }

    // Checks

    private fun checkFile(file: File, english: EnglishFile, format: Format, displayRoot: File?): List<Error> {
        val translated = try {
            parseTranslation(file.readText())
        } catch (e: Exception) {
            return listOf(Error(display(displayRoot, file), "(file)", "This file is not valid XML, so it was skipped."))
        }
        val errors = mutableListOf<Error>()
        for (key in translated.keys.sorted()) {
            val entry = translated.getValue(key)
            if (key in english.untranslatable) {
                errors.add(
                    Error(
                        display(displayRoot, file), key,
                        "This string is marked translatable=\"false\" in English, so it must not appear here.",
                    ),
                )
                continue
            }
            val want = english.entries[key]
            if (want == null) {
                errors.add(Error(display(displayRoot, file), key, "This key does not exist in the English file."))
                continue
            }
            when (entry) {
                is TransEntry.Text -> {
                    if (want.text == null) {
                        errors.add(
                            Error(
                                display(displayRoot, file), key,
                                "English has a plural here, but this translation has a single string.",
                            ),
                        )
                        continue
                    }
                    checkArgs(file, key, null, entry.text.args, want.text.args, errors, displayRoot)
                    checkKeeps(file, key, null, entry.text.text, want.keeps, errors, displayRoot)
                    checkMarkup(file, key, entry.text.text, want.text.text, format, errors, displayRoot)
                }
                is TransEntry.Group -> {
                    if (want.text != null) {
                        errors.add(
                            Error(
                                display(displayRoot, file), key,
                                "English has a single string here, but this translation has a plural.",
                            ),
                        )
                        continue
                    }
                    checkQuantities(file, key, entry.items.keys, errors, displayRoot)
                    for (quantity in entry.items.keys.sorted()) {
                        val got = entry.items.getValue(quantity)
                        val expected = want.items[quantity] ?: want.items["other"]
                        if (expected != null) {
                            checkArgs(file, key, quantity, got.args, expected.args, errors, displayRoot)
                        }
                        checkKeeps(file, key, quantity, got.text, want.keeps, errors, displayRoot)
                        checkMarkup(file, key, got.text, expected?.text.orEmpty(), format, errors, displayRoot)
                    }
                }
            }
        }
        return errors
    }

    private fun checkQuantities(file: File, key: String, quantities: Set<String>, errors: MutableList<Error>, displayRoot: File?) {
        val tag = LanguageGate.qualifierToTag(file.parentFile.name) ?: return
        val required = try {
            PluralRules.forLocale(ULocale.forLanguageTag(tag)).keywords
        } catch (e: Exception) {
            return
        }
        val missing = (required - quantities).sorted()
        if (missing.isNotEmpty()) {
            errors.add(
                Error(
                    display(displayRoot, file), key,
                    "This plural is missing quantities this language needs: ${missing.joinToString(", ")}.",
                ),
            )
        }
    }

    private fun checkArgs(
        file: File,
        key: String,
        quantity: String?,
        got: List<Pair<Int, Char>>,
        want: List<Pair<Int, Char>>,
        errors: MutableList<Error>,
        displayRoot: File?,
    ) {
        val where = if (quantity == null) "" else "The \"$quantity\" form "
        if (quantity in EXEMPT_QUANTITIES) {
            // Android lets one, zero and two leave the number placeholder out.
            if (dropsOnly(got, want)) return
            errors.add(
                Error(
                    display(displayRoot, file), key,
                    "${where.ifEmpty { "The placeholders " }}may only leave placeholders out, " +
                        "never change or add them. English has ${formatArgs(want)}; " +
                        "this translation has ${formatArgs(got)}.",
                ),
            )
            return
        }
        if (got != want) {
            errors.add(
                Error(
                    display(displayRoot, file), key,
                    "${where}has different placeholders from English. English has ${formatArgs(want)}; " +
                        "this translation has ${formatArgs(got)}.",
                ),
            )
        }
    }

    private fun checkKeeps(
        file: File,
        key: String,
        quantity: String?,
        text: String,
        keeps: List<String>,
        errors: MutableList<Error>,
        displayRoot: File?,
    ) {
        for (keep in keeps) {
            if (!text.contains(keep)) {
                val where = if (quantity == null) "" else "The \"$quantity\" form "
                errors.add(
                    Error(
                        display(displayRoot, file), key,
                        "${where}must keep \"$keep\" exactly as in English, with the same capitals.",
                    ),
                )
            }
        }
    }

    private fun checkMarkup(
        file: File,
        key: String,
        text: String,
        english: String,
        format: Format,
        errors: MutableList<Error>,
        displayRoot: File?,
    ) {
        if (text.contains("@string/")) {
            errors.add(Error(display(displayRoot, file), key, "This translation must not use @string/ references."))
        }
        // A literal < or > (written &lt; or &gt;) is fine where English has the same one,
        // such as "Settings > System". Any extra one is a tag that does not belong.
        if (text.count { it == '<' } > english.count { it == '<' } ||
            text.count { it == '>' } > english.count { it == '>' }
        ) {
            errors.add(Error(display(displayRoot, file), key, "This translation has an unbalanced tag. Tags must open and close in pairs."))
        }
        when (format) {
            Format.ANDROID -> checkAndroidEscapes(file, key, text, errors, displayRoot)
            Format.COMPOSE -> checkComposeEscapes(file, key, text, errors, displayRoot)
        }
    }

    private fun checkComposeEscapes(file: File, key: String, text: String, errors: MutableList<Error>, displayRoot: File?) {
        if (text.contains("\\'") || text.contains("\\\"")) {
            errors.add(
                Error(
                    display(displayRoot, file), key,
                    "This translation puts a backslash before an apostrophe or quote. " +
                        "The Companion shows that backslash, so write ' and \" without it.",
                ),
            )
        }
        if (text.contains("%%")) {
            errors.add(
                Error(
                    display(displayRoot, file), key,
                    "This translation has %%. The Companion shows that as two percent signs, so write a single %.",
                ),
            )
        }
    }

    private fun checkAndroidEscapes(file: File, key: String, text: String, errors: MutableList<Error>, displayRoot: File?) {
        for (i in text.indices) {
            val c = text[i]
            if ((c == '\'' || c == '"') && (i == 0 || text[i - 1] != '\\')) {
                val what = if (c == '\'') "an unescaped apostrophe. Write \\' instead" else "an unescaped quote. Write \\\" instead"
                errors.add(Error(display(displayRoot, file), key, "This translation has $what."))
                break
            }
        }
        var i = 0
        while (i < text.length) {
            if (text[i] != '%') {
                i++
                continue
            }
            val placeholder = PLACEHOLDER.find(text.substring(i))?.takeIf { it.range.first == 0 }
            if (placeholder != null) {
                i += placeholder.value.length
                continue
            }
            if (i + 1 < text.length && text[i + 1] == '%') {
                i += 2
                continue
            }
            errors.add(
                Error(
                    display(displayRoot, file), key,
                    "This translation has a % that is not a placeholder like %1\$s. Write %% for a percent sign.",
                ),
            )
            break
        }
    }

    /** True when [got] only leaves placeholders out, never changing or adding any. */
    private fun dropsOnly(got: List<Pair<Int, Char>>, want: List<Pair<Int, Char>>): Boolean {
        val remaining = want.toMutableList()
        for (arg in got) {
            if (!remaining.remove(arg)) return false
        }
        return true
    }

    private fun formatArgs(args: List<Pair<Int, Char>>): String =
        if (args.isEmpty()) "no placeholders"
        else args.joinToString(", ") { "%${it.first}\$${it.second}" }

    private fun display(root: File?, file: File): String {
        if (root == null) return file.path
        return try {
            root.toPath().relativize(file.toPath()).toString()
        } catch (e: IllegalArgumentException) {
            file.path
        }
    }

    private fun barPercent(bar: Double): String = "${Math.round(bar * 100)}%"
}
