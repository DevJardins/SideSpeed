package sidespeed.l10n

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class TranslationCheckTest {

    @get:Rule
    val tmp = TemporaryFolder()

    companion object {
        private const val D = "$"
    }

    private fun resource(name: String): String =
        javaClass.getResource(name)!!.readText()

    private fun layout(
        translations: Map<String, String>,
        format: TranslationCheck.Format = TranslationCheck.Format.ANDROID,
    ): TranslationCheck.Module {
        val root = tmp.newFolder()
        val english = resource("english.xml")
        val englishFile = File(root, "english.xml").apply { writeText(english) }
        val resDir = File(root, "res").apply { mkdir() }
        File(resDir, "values").apply { mkdir() }.resolve("strings.xml").apply { writeText(english) }
        for ((folder, xml) in translations) {
            File(resDir, folder).apply { mkdir() }.resolve("strings.xml").apply { writeText(xml) }
        }
        return TranslationCheck.Module("TV", englishFile, resDir, format)
    }

    private fun check(
        translations: Map<String, String>,
        baseRef: String? = null,
        changedKeys: Map<String, Set<String>> = emptyMap(),
        format: TranslationCheck.Format = TranslationCheck.Format.ANDROID,
    ): TranslationCheck.Result {
        val module = layout(translations, format)
        val byPath = changedKeys.mapKeys { (folder, _) ->
            File(module.resDir, folder).resolve("strings.xml").path
        }
        return TranslationCheck.check(listOf(module), changedKeys = byPath, baseRef = baseRef)
    }

    private fun fr(overrides: Map<String, String> = emptyMap(), extras: String = ""): String {
        var xml = resource("valid-fr.xml")
        for ((key, replacement) in overrides) {
            // Replace the whole string or item element carrying this name or quantity marker.
            // The replacement is literal XML: escape the $ and \ that Regex.replace would read.
            val literal = Regex.escapeReplacement(replacement)
            xml = xml.replace(Regex("(<string name=\"$key\">.*?</string>)", RegexOption.DOT_MATCHES_ALL), literal)
                .replace(Regex("(<item quantity=\"$key\">.*?</item>)", RegexOption.DOT_MATCHES_ALL), literal)
        }
        if (extras.isNotEmpty()) xml = xml.replace("</resources>", "$extras\n</resources>")
        return xml
    }

    @Test
    fun valid_translation_passes() {
        val result = check(mapOf("values-fr" to resource("valid-fr.xml")))
        assertTrue(result.errors.isEmpty())
    }

    @Test
    fun full_arabic_passes() {
        val result = check(mapOf("values-ar" to resource("full-ar.xml")))
        assertTrue(result.errors.isEmpty())
    }

    @Test
    fun invalid_xml_fails() {
        val result = check(mapOf("values-de" to "<resources><string name=\"hello\">Hallo"))
        assertEquals(1, result.errors.size)
        val error = result.errors.single()
        assertTrue(error.file.contains("values-de"))
        assertTrue(error.message.contains("not valid XML"))
        assertEquals("values-de/strings.xml: (file): ${error.message}", error.toString().substringAfterLast("res/"))
    }

    @Test
    fun placeholder_count_mismatch_fails() {
        val xml = fr(mapOf("careful" to "<string name=\"careful\">Soyez prudent</string>"))
        val result = check(mapOf("values-fr" to xml))
        assertTrue(result.errors.any { it.key == "careful" && it.message.contains("placeholder") })
    }

    @Test
    fun placeholder_index_mismatch_fails() {
        val xml = fr(mapOf("careful" to "<string name=\"careful\">Soyez prudent avec %2${D}s</string>"))
        val result = check(mapOf("values-fr" to xml))
        assertTrue(result.errors.any { it.key == "careful" })
    }

    @Test
    fun placeholder_type_mismatch_fails() {
        val xml = fr(mapOf("careful" to "<string name=\"careful\">Soyez prudent avec %1${D}d</string>"))
        val result = check(mapOf("values-fr" to xml))
        assertTrue(result.errors.any { it.key == "careful" })
    }

    @Test
    fun plural_item_placeholder_mismatch_fails() {
        val xml = fr(mapOf("other" to "<item quantity=\"other\">%1${D}s articles</item>"))
        val result = check(mapOf("values-fr" to xml))
        assertTrue(result.errors.any { it.key == "items" && it.message.contains("other") })
    }

    @Test
    fun plural_one_may_drop_number_placeholder() {
        val ar = resource("full-ar.xml")
        // Arabic "one" carries no placeholder at all; Android allows this for one, zero and two.
        val result = check(mapOf("values-ar" to ar))
        assertTrue(result.errors.isEmpty())
    }

    @Test
    fun plural_other_may_not_drop_placeholder() {
        val xml = fr(mapOf("other" to "<item quantity=\"other\">articles</item>"))
        val result = check(mapOf("values-fr" to xml))
        assertTrue(result.errors.any { it.key == "items" })
    }

    @Test
    fun missing_plural_quantity_fails() {
        val partialAr = """
            <resources>
                <string name="hello">مرحبا من SideSpeed</string>
                <string name="careful">كن حذرا مع %1${D}s</string>
                <plurals name="items">
                    <item quantity="one">عنصر واحد</item>
                    <item quantity="other">%1${D}d عنصر</item>
                </plurals>
            </resources>
        """.trimIndent()
        val result = check(mapOf("values-ar" to partialAr))
        val error = result.errors.single { it.key == "items" }
        assertTrue(error.message.contains("zero"))
        assertTrue(error.message.contains("few"))
    }

    @Test
    fun keep_name_missing_fails() {
        val xml = fr(mapOf("hello" to "<string name=\"hello\">Bonjour</string>"))
        val result = check(mapOf("values-fr" to xml))
        assertTrue(result.errors.any { it.key == "hello" && it.message.contains("SideSpeed") })
    }

    @Test
    fun keep_name_altered_fails() {
        val xml = fr(mapOf("hello" to "<string name=\"hello\">Bonjour de sidespeed</string>"))
        val result = check(mapOf("values-fr" to xml))
        assertTrue(result.errors.any { it.key == "hello" })
    }

    @Test
    fun untranslatable_key_present_fails() {
        val xml = fr(extras = "<string name=\"debug_only\">D\u00e9bogage</string>")
        val result = check(mapOf("values-fr" to xml))
        assertTrue(result.errors.any { it.key == "debug_only" && it.message.contains("translatable") })
    }

    @Test
    fun unescaped_apostrophe_fails() {
        val xml = fr(mapOf("hello" to "<string name=\"hello\">Bonjour de l'SideSpeed</string>"))
        val result = check(mapOf("values-fr" to xml))
        assertTrue(result.errors.any { it.key == "hello" && it.message.contains("apostrophe") })
    }

    @Test
    fun escaped_apostrophe_passes() {
        val xml = fr(mapOf("hello" to "<string name=\"hello\">Bonjour de l\\'SideSpeed</string>"))
        val result = check(mapOf("values-fr" to xml))
        assertTrue(result.errors.none { it.key == "hello" })
    }

    @Test
    fun lone_percent_fails() {
        val xml = fr(mapOf("hello" to "<string name=\"hello\">Bonjour de SideSpeed 100%</string>"))
        val result = check(mapOf("values-fr" to xml))
        assertTrue(result.errors.any { it.key == "hello" })
    }

    // Compose resources only unescape \n, \t, \uXXXX and \\, and only replace %1$s-style
    // placeholders, so \' shows its backslash and %% shows two percent signs.

    @Test
    fun compose_bare_apostrophe_and_quote_pass() {
        val xml = fr(mapOf("hello" to "<string name=\"hello\">L'app \"SideSpeed\"</string>"))
        val result = check(mapOf("values-fr" to xml), format = TranslationCheck.Format.COMPOSE)
        assertTrue(result.errors.none { it.key == "hello" })
    }

    @Test
    fun compose_escaped_apostrophe_fails() {
        val xml = fr(mapOf("hello" to "<string name=\"hello\">L\\'app SideSpeed</string>"))
        val result = check(mapOf("values-fr" to xml), format = TranslationCheck.Format.COMPOSE)
        assertTrue(result.errors.any { it.key == "hello" && it.message.contains("backslash") })
    }

    @Test
    fun compose_lone_percent_passes() {
        val xml = fr(mapOf("careful" to "<string name=\"careful\">Soyez prudent avec %1${D}s 100%</string>"))
        val result = check(mapOf("values-fr" to xml), format = TranslationCheck.Format.COMPOSE)
        assertTrue(result.errors.none { it.key == "careful" })
    }

    @Test
    fun compose_double_percent_fails() {
        val xml = fr(mapOf("careful" to "<string name=\"careful\">Soyez prudent avec %1${D}s 100%%</string>"))
        val result = check(mapOf("values-fr" to xml), format = TranslationCheck.Format.COMPOSE)
        assertTrue(result.errors.any { it.key == "careful" && it.message.contains("%%") })
    }

    @Test
    fun string_reference_fails() {
        val xml = fr(mapOf("hello" to "<string name=\"hello\">@string/hello SideSpeed</string>"))
        val result = check(mapOf("values-fr" to xml))
        assertTrue(result.errors.any { it.key == "hello" })
    }

    @Test
    fun unbalanced_tag_fails() {
        val xml = fr(mapOf("hello" to "<string name=\"hello\">Bonjour &lt;b&gt;de SideSpeed</string>"))
        val result = check(mapOf("values-fr" to xml))
        assertTrue(result.errors.any { it.key == "hello" })
    }

    @Test
    fun unknown_key_fails() {
        val xml = fr(extras = "<string name=\"ghost\">Fant\u00f4me</string>")
        val result = check(mapOf("values-fr" to xml))
        assertTrue(result.errors.any { it.key == "ghost" })
    }

    @Test
    fun missing_key_is_fine() {
        val xml = """
            <resources>
                <string name="hello">Bonjour de SideSpeed</string>
            </resources>
        """.trimIndent()
        val result = check(mapOf("values-fr" to xml))
        assertTrue(result.errors.none { it.key == "ghost" })
        assertTrue(result.errors.isEmpty())
    }

    @Test
    fun pseudo_locales_are_skipped() {
        val broken = "<resources><string name=\"hello\">broken"
        val result = check(
            mapOf(
                "values-en-rXA" to broken,
                "values-ar-rXB" to broken,
            ),
        )
        assertTrue(result.errors.isEmpty())
        assertTrue(!result.markdown.contains("en-XA"))
        assertTrue(!result.markdown.contains("ar-XB"))
    }

    @Test
    fun summary_groups_by_module_and_language() {
        val module = layout(mapOf("values-fr" to resource("valid-fr.xml")))
        val tv = module.copy(name = "TV")
        val companion = module.copy(name = "Companion")
        val result = TranslationCheck.check(listOf(tv, companion))
        assertTrue(result.markdown.contains("## TV"))
        assertTrue(result.markdown.contains("## Companion"))
        assertTrue(result.markdown.contains("fr"))
        assertTrue(result.markdown.contains("100%"))
        assertTrue(result.markdown.contains("in (at or above the 70% bar)"))
    }

    @Test
    fun summary_marks_languages_below_bar_out() {
        val xml = """
            <resources>
                <string name="hello">Bonjour de SideSpeed</string>
            </resources>
        """.trimIndent()
        val result = check(mapOf("values-fr" to xml))
        assertTrue(result.markdown.contains("out (below the 70% bar)"))
        assertTrue(result.markdown.contains("33%"))
    }

    @Test
    fun summary_lists_changed_and_safety_keys() {
        val module = layout(mapOf("values-fr" to resource("valid-fr.xml")))
        val frFile = File(module.resDir, "values-fr").resolve("strings.xml").path
        val result = TranslationCheck.check(
            listOf(module),
            baseRef = "origin/main",
            changedKeys = mapOf(frFile to setOf("hello", "careful")),
        )
        assertTrue(result.markdown.contains("2 strings changed"))
        assertTrue(result.markdown.contains("careful"))
        assertTrue(!result.markdown.contains("hello") || result.markdown.contains("Safety strings changed: careful"))
    }

    @Test
    fun summary_without_base_omits_changed_counts() {
        val result = check(mapOf("values-fr" to resource("valid-fr.xml")))
        assertTrue(!result.markdown.contains("changed"))
    }

    @Test
    fun changed_keys_detects_added_removed_and_edited() {
        val base = resource("english.xml")
        val edited = base.replace("Hello from SideSpeed", "Hello from SideSpeed!").replace(
            "<string name=\"careful\">Be careful with %1${D}s</string>",
            "",
        ) + ""
        val added = edited.replace("</resources>", "<string name=\"new_key\">New</string>\n</resources>")
        val changed = TranslationCheck.changedKeys(base, added)
        assertEquals(setOf("hello", "careful", "new_key"), changed)
    }

    @Test
    fun changed_keys_new_file_reports_all_keys() {
        val changed = TranslationCheck.changedKeys(null, resource("valid-fr.xml"))
        assertEquals(setOf("hello", "careful", "items"), changed)
    }
}
